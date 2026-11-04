package dev.braid.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (the workspace invite, which contains the PSK) with an AES-256-GCM key
 * that lives in the Android Keystore (hardware-backed TEE/StrongBox where available). The key is
 * non-exportable, so a copied app-data directory or an `adb backup` does not reveal the PSK.
 *
 * Why not Jetpack Security `EncryptedSharedPreferences`? It is deprecated (2024) and pulls Tink.
 * This is the ~40 lines it actually did for our use case.
 */
class SecretBox(private val alias: String = "braid.workspace.v1") {

    fun seal(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + cipher.iv.size + ct.size)
        out[0] = cipher.iv.size.toByte()
        System.arraycopy(cipher.iv, 0, out, 1, cipher.iv.size)
        System.arraycopy(ct, 0, out, 1 + cipher.iv.size, ct.size)
        return Base64.getEncoder().encodeToString(out)
    }

    fun open(sealed: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(sealed)
        val ivLen = bytes[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, ivLen))
        String(cipher.doFinal(bytes, 1 + ivLen, bytes.size - 1 - ivLen), Charsets.UTF_8)
    }.getOrNull() // key invalidated (e.g. device restore) -> treat as "no workspace"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
