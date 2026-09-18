package com.example.dsim

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the group password under an Android Keystore AES-256-GCM key (W14). The key is not bound
 * to user authentication because the daemon has to read it at boot, before unlock. The key never
 * leaves the Keystore; what SharedPreferences holds is only usable on this device, on this install.
 *
 * Failure policy (decided): if the key is gone (factory reset restore, Keystore wipe, OEM bug),
 * [open] returns null and the caller wipes the credentials so the user re-enters them. There is no
 * plaintext fallback for a previously sealed value.
 */
object CredentialVault {
    private const val TAG = "dSIM_Vault"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "dsim_cred_v1"
    private const val TAG_BITS = 128

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(createIfMissing: Boolean): SecretKey? {
        val ks = keyStore()
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!createIfMissing) return null
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    /** Null when the Keystore refuses (no key could be made); caller decides what to do. */
    fun seal(plaintext: String): String? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(createIfMissing = true))
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            CredentialCodec.frame(cipher.iv, ct) { Base64.encodeToString(it, Base64.NO_WRAP) }
        } catch (e: Exception) {
            Log.e(TAG, "seal failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Null = key lost or blob corrupt. Distinguishing the two buys nothing: both mean re-enter. */
    fun open(sealed: String): String? {
        val parts = CredentialCodec.unframe(sealed) { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return try {
            val k = key(createIfMissing = false) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, parts.iv))
            String(cipher.doFinal(parts.ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "open failed (key lost or blob corrupt): ${e.javaClass.simpleName}")
            null
        }
    }
}
