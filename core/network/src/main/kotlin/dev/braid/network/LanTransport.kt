package dev.braid.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import dev.braid.crdt.ReplicaId
import dev.braid.sync.DiscoveredPeer
import dev.braid.sync.FrameConnection
import dev.braid.sync.MeshTransport
import dev.braid.sync.StreamFrameConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Local-network transport: DNS-SD/mDNS (via [NsdManager]) for discovery and plain TCP sockets
 * for data (which is then wrapped in Noise by the sync layer, so TCP itself needs no TLS).
 *
 * ## Advertisement
 * Service type `_braid._tcp`, instance name `b-<discoveryTag>-<replicaHex>`.
 *  - `discoveryTag` is an HMAC of the workspace key, so peers in *other* workspaces are filtered
 *    out before any connection attempt, and an observer on the LAN cannot correlate the tag with
 *    a workspace name. It is not a security boundary (Noise is); it's an efficiency filter.
 *  - The replica id lets both sides apply the "smaller id dials" rule before connecting.
 *  We use the instance name rather than TXT records because TXT attributes are not returned by
 *  `onServiceFound` and `resolveService` historically allowed only one resolve at a time.
 *
 * ## Why NSD and not Nearby Connections / Wi-Fi Direct?
 *  - No Google Play Services dependency (works on de-Googled devices, emulators, F-Droid).
 *  - Interoperates with any mDNS stack, so a desktop peer could join with zero changes.
 *  - Wi-Fi Direct requires user-confirmed group formation and drops normal Wi-Fi on many devices.
 *  The transport sits behind [MeshTransport], so Nearby/BLE can be added without touching sync.
 */
class LanTransport(
    context: Context,
    private val scope: CoroutineScope,
    private val localReplica: ReplicaId,
    private val discoveryTag: String,
) : MeshTransport {

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _discovered = MutableStateFlow<Set<DiscoveredPeer>>(emptySet())
    override val discovered: Flow<Set<DiscoveredPeer>> = _discovered.asStateFlow()

    private val incomingChannel = Channel<FrameConnection>(Channel.BUFFERED)
    override val incoming: Flow<FrameConnection> = incomingChannel.receiveAsFlow()

    private val _status = MutableStateFlow(TransportStatus())
    val status: StateFlow<TransportStatus> = _status.asStateFlow()

    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var resolveJob: Job? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** Resolves are serialized: pre-API-34 NsdManager fails with FAILURE_ALREADY_ACTIVE otherwise. */
    private val resolveExecutor = Executors.newSingleThreadExecutor()
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)

    private val instancePrefix = "b-$discoveryTag-"
    private val myInstance = "$instancePrefix$localReplica"

    fun start() {
        if (server != null) return
        multicastLock = wifi.createMulticastLock("braid-mdns").apply { setReferenceCounted(false); acquire() }
        val ss = ServerSocket(0) // ephemeral port; advertised through mDNS
        server = ss
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val socket = runCatching { ss.accept() }.getOrElse { break }
                incomingChannel.send(StreamFrameConnection.over(socket))
            }
        }
        resolveJob = scope.launch { for (info in resolveQueue) resolve(info) }
        register(ss.localPort)
        discover()
        _status.update { it.copy(running = true, port = ss.localPort) }
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        acceptJob?.cancel()
        resolveJob?.cancel()
        while (resolveQueue.tryReceive().isSuccess) Unit // drop stale resolve requests
        runCatching { server?.close() }
        server = null
        multicastLock?.let { if (it.isHeld) it.release() }
        _discovered.value = emptySet()
        _status.value = TransportStatus()
    }

    override suspend fun dial(peer: DiscoveredPeer): FrameConnection = withContext(Dispatchers.IO) {
        val (host, port) = peer.address.substringBeforeLast(':') to peer.address.substringAfterLast(':').toInt()
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
        StreamFrameConnection.over(socket)
    }

    // ------------------------------------------------------------------ NSD plumbing

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = myInstance
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // The system may rename us on collision ("name (2)"); harmless, peers parse the prefix.
                _status.update { it.copy(advertisedAs = info.serviceName) }
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.w(TAG, "registration failed: $error")
                _status.update { it.copy(error = "Advertising failed ($error)") }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun discover() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, error: Int) {
                _status.update { it.copy(error = "Discovery failed ($error)") }
            }
            override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                val replica = parseReplica(info.serviceName) ?: return
                if (replica == localReplica) return
                resolveQueue.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val replica = parseReplica(info.serviceName) ?: return
                _discovered.update { set -> set.filterTo(HashSet()) { it.replica != replica } }
            }
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo) {
        val replica = parseReplica(info.serviceName) ?: return
        val done = Channel<Unit>(1)
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                done.trySend(Unit)
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = if (Build.VERSION.SDK_INT >= 34) {
                    resolved.hostAddresses.firstOrNull()?.hostAddress
                } else {
                    resolved.host?.hostAddress
                }
                if (host != null) {
                    // "host:port"; dial() splits on the *last* colon, so IPv6 literals work unbracketed.
                    val peer = DiscoveredPeer(replica, "${host.substringBefore('%')}:${resolved.port}")
                    _discovered.update { set -> set.filterTo(HashSet()) { it.replica != replica } + peer }
                }
                done.trySend(Unit)
            }
        }
        if (Build.VERSION.SDK_INT >= 34) nsd.resolveService(info, resolveExecutor, callback) else nsd.resolveService(info, callback)
        done.receive()
    }

    private fun parseReplica(serviceName: String): ReplicaId? {
        if (!serviceName.startsWith(instancePrefix)) return null
        val hex = serviceName.removePrefix(instancePrefix).take(16)
        return runCatching { ReplicaId.parse(hex) }.getOrNull()
    }

    companion object {
        const val SERVICE_TYPE = "_braid._tcp."
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val TAG = "LanTransport"
    }
}

data class TransportStatus(
    val running: Boolean = false,
    val port: Int = 0,
    val advertisedAs: String? = null,
    val error: String? = null,
)
