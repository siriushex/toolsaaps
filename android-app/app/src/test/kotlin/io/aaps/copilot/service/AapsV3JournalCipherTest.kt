package io.aaps.copilot.service

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class AapsV3JournalCipherTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test fun actualFormatRoundTripsWithUniqueNoncesAndBindsRowAndRevision() {
        val cipher = KeystoreAapsV3JournalCipher { key }
        val plain = "synthetic-private-state".toByteArray()
        val sealed = cipher.seal("owner", 1, plain)
        assertArrayEquals(plain, cipher.open("owner", 1, sealed))
        assertFalse(sealed.contentEquals(cipher.seal("owner", 1, plain)))
        assertTrue(runCatching { cipher.open("other", 1, sealed) }.isFailure)
        assertTrue(runCatching { cipher.open("owner", 2, sealed) }.isFailure)
    }

    @Test fun damagedVersionTagAndWrongKeyCannotBeRead() {
        val cipher = KeystoreAapsV3JournalCipher { key }
        val sealed = cipher.seal("owner", 1, byteArrayOf(1, 2, 3))
        assertTrue(runCatching { cipher.open("owner", 1, sealed.copyOf().also { it[0] = 2 }) }.isFailure)
        assertTrue(runCatching { cipher.open("owner", 1, sealed.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }) }.isFailure)
        assertTrue(runCatching { cipher.open("owner", 1, sealed.copyOf(12)) }.isFailure)
        assertTrue(runCatching { KeystoreAapsV3JournalCipher { SecretKeySpec(ByteArray(32), "AES") }
            .open("owner", 1, sealed) }.isFailure)
    }

    @Test fun readingAnExistingRowNeverAsksForAReplacementKey() {
        val sealed = KeystoreAapsV3JournalCipher { key }.seal("owner", 1, byteArrayOf(1))
        val requests = mutableListOf<Boolean>()
        val missing = KeystoreAapsV3JournalCipher { create -> requests += create; error("key absent") }
        assertTrue(runCatching { missing.open("owner", 1, sealed) }.isFailure)
        assertEquals(listOf(false), requests)
    }
}
