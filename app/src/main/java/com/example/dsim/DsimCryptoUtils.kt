package com.example.dsim

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object DsimCryptoUtils {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val KEY_SIZE = 32
    private const val IV_SIZE = 16

    private fun deriveKeyFromTopic(topic: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val paddedTopic = topic.padEnd(KEY_SIZE, 'd')
        val keyBytes = digest.digest(paddedTopic.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, ALGORITHM)
    }

    fun encryptMessage(plaintext: String, topic: String): String {
        return try {
            val keySpec = deriveKeyFromTopic(topic)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            
            val iv = ByteArray(IV_SIZE)
            SecureRandom().nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val combined = iv + encryptedBytes
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            "ENCRYPTION_ERROR"
        }
    }

    fun decryptMessage(ciphertextBase64: String, topic: String): String? {
        return try {
            val combined = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
            if (combined.size < IV_SIZE) return null

            val iv = combined.copyOfRange(0, IV_SIZE)
            val encryptedBytes = combined.copyOfRange(IV_SIZE, combined.size)
            
            val keySpec = deriveKeyFromTopic(topic)
            val ivSpec = IvParameterSpec(iv)
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
