package dev.braid.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Length-prefixed framing (`u32 big-endian length` + payload) over blocking streams.
 *
 * Blocking java.io on [Dispatchers.IO] rather than NIO: a phone has at most a handful of peers,
 * so one parked thread per direction is cheaper in code and bugs than a selector loop.
 */
class StreamFrameConnection(
    input: InputStream,
    output: OutputStream,
    override val remoteLabel: String,
    private val onClose: () -> Unit = {},
) : FrameConnection {
    private val inp = DataInputStream(input.buffered())
    private val out = DataOutputStream(output.buffered())
    private val writeLock = Mutex()
    private val readLock = Mutex()

    override suspend fun send(frame: ByteArray) {
        require(frame.size <= FrameLimits.MAX_FRAME_BYTES) { "frame too large: ${frame.size}" }
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                out.writeInt(frame.size)
                out.write(frame)
                out.flush()
            }
        }
    }

    override suspend fun receive(): ByteArray = readLock.withLock {
        withContext(Dispatchers.IO) {
            val len = inp.readInt()
            if (len < 0 || len > FrameLimits.MAX_FRAME_BYTES) throw IOException("bad frame length $len")
            ByteArray(len).also { inp.readFully(it) }
        }
    }

    override fun close() {
        runCatching { inp.close() }
        runCatching { out.close() }
        onClose()
    }

    companion object {
        fun over(socket: Socket): StreamFrameConnection {
            socket.tcpNoDelay = true // keystroke-sized frames must not wait for Nagle
            socket.keepAlive = true
            return StreamFrameConnection(
                socket.getInputStream(),
                socket.getOutputStream(),
                "${socket.inetAddress.hostAddress}:${socket.port}",
            ) { runCatching { socket.close() } }
        }
    }
}
