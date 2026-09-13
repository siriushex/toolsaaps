package io.aaps.copilot.security

import org.junit.Assert.*
import org.junit.Test

class ServerAiDeviceKeyTest {
    private class Backend : ServerAiKeyBackend {
        var present = false
        var hardware = true
        var creates = 0
        var removes = 0
        var challenge: ByteArray? = null
        override fun exists() = present
        override fun create(challenge: ByteArray) {
            this.challenge = challenge.copyOf()
            creates++
            present = true
        }
        override fun hardwareBacked() = hardware
        override fun certificateChain() = listOf("synthetic-certificate")
        override fun sign(message: ByteArray) = "synthetic-signature"
        override fun delete() { removes++; present = false }
    }

    @Test fun creationRequiresChallengeAndNeverReplacesExistingKey() {
        val backend = Backend()
        val key = ServerAiDeviceKey(backend)
        assertThrows(IllegalArgumentException::class.java) { key.create(ByteArray(0)) }
        assertEquals(0, backend.creates)
        val challenge = ByteArray(32) { it.toByte() }
        assertEquals(listOf("synthetic-certificate"), key.create(challenge))
        assertArrayEquals(challenge, backend.challenge)
        assertThrows(IllegalStateException::class.java) { key.create(ByteArray(32)) }
        assertEquals(1, backend.creates)
        assertEquals(0, backend.removes)
    }

    @Test fun softwareKeyIsRejectedAndOnlyNewKeyRemoved() {
        val backend = Backend().apply { hardware = false }
        assertThrows(IllegalStateException::class.java) { ServerAiDeviceKey(backend).create(ByteArray(32)) }
        assertEquals(1, backend.removes)
        assertFalse(backend.present)
    }

    @Test fun absentKeyCannotSignOrSilentlyRegenerate() {
        val backend = Backend()
        assertThrows(IllegalStateException::class.java) { ServerAiDeviceKey(backend).sign(byteArrayOf(1)) }
        assertEquals(0, backend.creates)
    }
}
