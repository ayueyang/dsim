package com.example.dsim

/**
 * Wire-free framing for sealed credentials (W14): `v1:` + base64(iv || ciphertext+tag).
 * Pure Kotlin so the framing is unit-testable; the AES-GCM itself lives in [CredentialVault].
 */
object CredentialCodec {
    const val PREFIX_V1 = "v1:"
    const val IV_LENGTH = 12
    /** GCM tag is 16 bytes, so a valid body is always iv + at least the tag. */
    private const val MIN_BODY = IV_LENGTH + 16

    data class Sealed(val iv: ByteArray, val ciphertext: ByteArray)

    fun isSealed(value: String?): Boolean = value != null && value.startsWith(PREFIX_V1)

    fun frame(iv: ByteArray, ciphertext: ByteArray, encode: (ByteArray) -> String): String {
        require(iv.size == IV_LENGTH) { "iv must be $IV_LENGTH bytes" }
        return PREFIX_V1 + encode(iv + ciphertext)
    }

    /** Null for anything that is not a well-formed v1 frame. Never throws. */
    fun unframe(value: String, decode: (String) -> ByteArray): Sealed? {
        if (!value.startsWith(PREFIX_V1)) return null
        val body = try {
            decode(value.substring(PREFIX_V1.length))
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (body.size < MIN_BODY) return null
        return Sealed(body.copyOfRange(0, IV_LENGTH), body.copyOfRange(IV_LENGTH, body.size))
    }
}
