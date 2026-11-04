package dev.braid.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Everything a device needs to join a workspace, transferred out-of-band (QR code or link).
 *
 * Format: `braid://join/v1/<workspaceId>/<base64url(psk)>/<name>#<checksum>`
 *  - The PSK *is* the membership credential, so the QR code is a secret: it is shown only on an
 *    explicit "Invite" screen with FLAG_SECURE (no screenshots / recents thumbnail).
 *  - A 4-byte SHA-256 checksum catches truncated or mistyped links before a confusing
 *    "authentication failed" at handshake time.
 */
data class Invite(val workspaceId: String, val psk: ByteArray, val workspaceName: String) {
    init {
        require(WORKSPACE_ID.matches(workspaceId)) { "bad workspace id" }
        require(psk.size == 32) { "psk must be 32 bytes" }
    }

    /**
     * Opaque discovery tag advertised on the LAN. Derived from the PSK with HMAC, so devices
     * outside the workspace cannot tell which workspace a device belongs to, and cannot forge
     * the tag to attract connections (they'd fail the handshake anyway).
     */
    val discoveryTag: String
        get() {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(psk, "HmacSHA256"))
            val d = mac.doFinal("braid-discovery-v1".toByteArray())
            return d.copyOf(6).joinToString("") { "%02x".format(it) }
        }

    fun toUri(): String {
        val key = B64.encodeToString(psk)
        val name = java.net.URLEncoder.encode(workspaceName, "UTF-8").replace("+", "%20")
        val body = "$PREFIX$workspaceId/$key/$name"
        return "$body#${checksum(body)}"
    }

    override fun equals(other: Any?): Boolean =
        other is Invite && workspaceId == other.workspaceId && psk.contentEquals(other.psk) && workspaceName == other.workspaceName

    override fun hashCode(): Int = workspaceId.hashCode() * 31 + psk.contentHashCode()

    override fun toString(): String = "Invite($workspaceId, '$workspaceName', psk=<redacted>)" // never log secrets

    companion object {
        private const val PREFIX = "braid://join/v1/"
        private val WORKSPACE_ID = Regex("[a-z0-9]{12}")
        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val random = SecureRandom()

        fun create(name: String): Invite {
            val id = buildString { repeat(12) { append("abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)]) } }
            return Invite(id, ByteArray(32).also(random::nextBytes), name.trim().ifEmpty { "Workspace" }.take(40))
        }

        fun parse(uri: String): Result<Invite> = runCatching {
            val trimmed = uri.trim()
            require(trimmed.startsWith(PREFIX)) { "Not a Braid invite" }
            val hash = trimmed.lastIndexOf('#')
            require(hash > 0) { "Invite is incomplete" }
            val body = trimmed.substring(0, hash)
            require(checksum(body) == trimmed.substring(hash + 1)) { "Invite is corrupted (checksum mismatch)" }
            val parts = body.removePrefix(PREFIX).split('/')
            require(parts.size == 3) { "Invite is malformed" }
            Invite(
                workspaceId = parts[0],
                psk = Base64.getUrlDecoder().decode(parts[1]),
                workspaceName = java.net.URLDecoder.decode(parts[2], "UTF-8"),
            )
        }

        private fun checksum(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).copyOf(4).joinToString("") { "%02x".format(it) }
    }
}
