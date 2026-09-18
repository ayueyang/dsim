package com.example.dsim

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * DSM3 byte-level contract via [DsimCryptoUtils.seal]/[DsimCryptoUtils.open] (no android Base64).
 * Failure paths log through android.util.Log; the JVM stub returns defaults (see testOptions).
 */
class DsimCryptoUtilsTest {
    private val magic = "DSM3".toByteArray(Charsets.US_ASCII)

    @Before fun resetCache() = DsimCryptoUtils.clearKeyCacheForTest()

    @Test fun roundTripRestoresPlaintext() {
        val plain = "你好 dSIM — round trip ✓".toByteArray(Charsets.UTF_8)
        val sealed = DsimCryptoUtils.seal(plain, "pw-1")
        assertArrayEquals(plain, DsimCryptoUtils.open(sealed, "pw-1"))
    }

    @Test fun layoutIsMagicIvCiphertextTag() {
        val sealed = DsimCryptoUtils.seal(ByteArray(0), "pw-1")
        assertArrayEquals(magic, sealed.copyOfRange(0, 4))
        // 4 magic + 12 IV + 0 body + 16 tag
        assertEquals(32, sealed.size)
    }

    @Test fun ivIsFreshPerMessage() {
        val a = DsimCryptoUtils.seal("same".toByteArray(), "pw-1")
        val b = DsimCryptoUtils.seal("same".toByteArray(), "pw-1")
        assertFalse(a.copyOfRange(4, 16).contentEquals(b.copyOfRange(4, 16)))
        assertFalse(a.contentEquals(b))
    }

    @Test fun wrongPasswordReturnsNull() {
        val sealed = DsimCryptoUtils.seal("secret".toByteArray(), "pw-1")
        assertNull(DsimCryptoUtils.open(sealed, "pw-2"))
    }

    @Test fun tamperedBodyOrMagicReturnsNull() {
        val sealed = DsimCryptoUtils.seal("secret".toByteArray(), "pw-1")
        val body = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertNull(DsimCryptoUtils.open(body, "pw-1"))
        val dsm2 = sealed.copyOf().also { it[3] = '2'.code.toByte() }
        assertNull("DSM2 read path must be gone", DsimCryptoUtils.open(dsm2, "pw-1"))
    }

    @Test fun shortOrGarbageInputReturnsNull() {
        assertNull(DsimCryptoUtils.open(ByteArray(0), "pw-1"))
        assertNull(DsimCryptoUtils.open(magic + ByteArray(12), "pw-1"))          // header only
        assertNull(DsimCryptoUtils.open(ByteArray(64) { 0x41 }, "pw-1"))         // no magic
    }

    @Test fun masterKeyIsDerivedOnceAndCachedPerPassword() {
        assertFalse(DsimCryptoUtils.isKeyCached("pw-1"))
        val k1 = DsimCryptoUtils.masterKey("pw-1")
        assertTrue(DsimCryptoUtils.isKeyCached("pw-1"))
        assertSame("cache must hand back the same array, not re-derive", k1, DsimCryptoUtils.masterKey("pw-1"))
        assertEquals(32, k1.size)
        assertFalse(k1.contentEquals(DsimCryptoUtils.masterKey("pw-2")))
    }

    @Test fun cacheEvictsBeyondLimit() {
        (1..5).forEach { DsimCryptoUtils.masterKey("pw-$it") }
        assertFalse("oldest entry evicted at limit 4", DsimCryptoUtils.isKeyCached("pw-1"))
        assertTrue(DsimCryptoUtils.isKeyCached("pw-5"))
    }
}
