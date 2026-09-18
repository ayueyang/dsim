package com.example.dsim

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CredentialCodecTest {
    private val enc: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) }
    private val dec: (String) -> ByteArray = { Base64.getDecoder().decode(it) }

    @Test fun frameAndUnframeRoundTrip() {
        val iv = ByteArray(12) { it.toByte() }
        val ct = ByteArray(40) { (it * 3).toByte() }
        val framed = CredentialCodec.frame(iv, ct, enc)
        assertTrue(framed.startsWith("v1:"))
        val s = CredentialCodec.unframe(framed, dec)!!
        assertArrayEquals(iv, s.iv)
        assertArrayEquals(ct, s.ciphertext)
    }

    @Test fun isSealedOnlyForPrefix() {
        assertTrue(CredentialCodec.isSealed("v1:abc"))
        assertFalse(CredentialCodec.isSealed("plain-password"))
        assertFalse(CredentialCodec.isSealed(null))
        assertFalse(CredentialCodec.isSealed(""))
    }

    @Test fun unframeRejectsGarbage() {
        assertNull(CredentialCodec.unframe("plain", dec))
        assertNull(CredentialCodec.unframe("v1:!!!not-base64!!!", dec))
        // too short: iv only, no tag
        assertNull(CredentialCodec.unframe("v1:" + enc(ByteArray(12)), dec))
        assertNull(CredentialCodec.unframe("v1:" + enc(ByteArray(27)), dec))
        assertEquals(28, (CredentialCodec.unframe("v1:" + enc(ByteArray(28)), dec)!!.let { it.iv.size + it.ciphertext.size }))
    }

    @Test(expected = IllegalArgumentException::class)
    fun frameRejectsWrongIvLength() {
        CredentialCodec.frame(ByteArray(16), ByteArray(20), enc)
    }
}
