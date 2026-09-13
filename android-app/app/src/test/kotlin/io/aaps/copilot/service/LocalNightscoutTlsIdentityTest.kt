package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LocalNightscoutTlsIdentityTest {

    @Test
    fun generatedIdentityIncludesIndependentRandomApiSecretWithoutStringFields() {
        val first = LocalNightscoutTlsIdentityGenerator.generate()
        val second = LocalNightscoutTlsIdentityGenerator.generate()

        try {
            assertThat(first.apiSecretBytes).isNotEmpty()
            assertThat(first.apiSecretBytes).isNotEqualTo(second.apiSecretBytes)
            assertThat(first.passwordBytes).isNotEqualTo(second.passwordBytes)
            assertThat(LocalNightscoutTlsIdentityRecord::class.java.declaredFields.map { it.type })
                .doesNotContain(String::class.java)
        } finally {
            first.clear()
            second.clear()
        }
    }

    @Test
    fun binaryCodecRoundTripCarriesApiSecretAndSupportsExplicitWiping() {
        val generated = LocalNightscoutTlsIdentityGenerator.generate()
        val encoded = LocalNightscoutTlsIdentityRecordCodec.encode(generated)
        val decoded = LocalNightscoutTlsIdentityRecordCodec.decode(encoded)

        try {
            assertThat(decoded.passwordBytes).isEqualTo(generated.passwordBytes)
            assertThat(decoded.apiSecretBytes).isEqualTo(generated.apiSecretBytes)
            assertThat(decoded.pkcs12Bytes).isEqualTo(generated.pkcs12Bytes)
        } finally {
            encoded.fill(0)
            decoded.clear()
            generated.clear()
        }
    }

    @Test
    fun generatedIdentityIsKeyManagerCompatibleWithValidCaChainAndLoopbackSans() {
        val loaded = LocalNightscoutTlsIdentityRepository(MemoryAtomicStorage()).load()

        try {
            assertThat(loaded.keyStore.isKeyEntry(LocalNightscoutTls.KEY_ALIAS)).isTrue()
            assertThat(loaded.keyStore.getKey(LocalNightscoutTls.KEY_ALIAS, loaded.password)).isNotNull()
            assertThat(loaded.serverCertificate.basicConstraints).isEqualTo(-1)
            assertThat(loaded.caCertificate.basicConstraints).isAtLeast(0)
            loaded.serverCertificate.verify(loaded.caCertificate.publicKey)
            loaded.caCertificate.verify(loaded.caCertificate.publicKey)
            assertThat(loaded.serverCertificate.issuerX500Principal)
                .isEqualTo(loaded.caCertificate.subjectX500Principal)
            assertThat(subjectAlternativeNames(loaded.serverCertificate))
                .containsAtLeast("localhost", "127.0.0.1")
            assertThat(loaded.serverCertificate.serialNumber).isGreaterThan(BigInteger.ZERO)
            assertThat(loaded.caCertificate.serialNumber).isGreaterThan(BigInteger.ZERO)
            assertThat(loaded.serverCertificate.serialNumber)
                .isNotEqualTo(loaded.caCertificate.serialNumber)
            assertThat(validityMs(loaded.serverCertificate))
                .isAtMost(LocalNightscoutTlsIdentityGenerator.SERVER_VALIDITY_MS)
            assertThat(validityMs(loaded.caCertificate))
                .isAtMost(LocalNightscoutTlsIdentityGenerator.CA_VALIDITY_MS)
        } finally {
            loaded.clearSecrets()
        }
    }

    @Test
    fun firstGenerationPersistsAndRestartReusesIdentityAndApiSecret() {
        val storage = MemoryAtomicStorage()
        val generations = AtomicInteger()
        val generator = {
            generations.incrementAndGet()
            LocalNightscoutTlsIdentityGenerator.generate()
        }

        val first = LocalNightscoutTlsIdentityRepository(storage, generator).load()
        val restarted = LocalNightscoutTlsIdentityRepository(storage, generator).load()

        try {
            assertThat(generations.get()).isEqualTo(1)
            assertThat(restarted.serverCertificate.encoded)
                .isEqualTo(first.serverCertificate.encoded)
            assertThat(restarted.caCertificate.encoded).isEqualTo(first.caCertificate.encoded)
            assertThat(restarted.apiSecretBytes).isEqualTo(first.apiSecretBytes)
        } finally {
            first.clearSecrets()
            restarted.clearSecrets()
        }
    }

    @Test
    fun independentEmptyStoresGenerateDifferentIdentityPasswordAndApiSecret() {
        val first = LocalNightscoutTlsIdentityRepository(MemoryAtomicStorage()).load()
        val second = LocalNightscoutTlsIdentityRepository(MemoryAtomicStorage()).load()

        try {
            assertThat(sha256(first.serverCertificate.encoded))
                .isNotEqualTo(sha256(second.serverCertificate.encoded))
            assertThat(sha256(first.caCertificate.encoded))
                .isNotEqualTo(sha256(second.caCertificate.encoded))
            assertThat(first.password).isNotEqualTo(second.password)
            assertThat(first.apiSecretBytes).isNotEqualTo(second.apiSecretBytes)
        } finally {
            first.clearSecrets()
            second.clearSecrets()
        }
    }

    @Test
    fun concurrentFirstAccessAcceptsOneGeneratedIdentity() = runBlocking {
        val storage = MemoryAtomicStorage()
        val generations = AtomicInteger()
        val generator = {
            generations.incrementAndGet()
            LocalNightscoutTlsIdentityGenerator.generate()
        }

        val loaded = List(12) {
            async(Dispatchers.Default) {
                LocalNightscoutTlsIdentityRepository(storage, generator).load()
            }
        }.awaitAll()

        try {
            assertThat(generations.get()).isEqualTo(1)
            assertThat(loaded.map { sha256(it.serverCertificate.encoded) }.distinct()).hasSize(1)
            assertThat(loaded.map { sha256(it.caCertificate.encoded) }.distinct()).hasSize(1)
            assertThat(loaded.map { it.apiSecretBytes.toList() }.distinct()).hasSize(1)
        } finally {
            loaded.forEach(LocalNightscoutTlsLoadedIdentity::clearSecrets)
        }
    }

    @Test
    fun corruptRecordFailsClosedWithoutGeneratingReplacement() {
        val storage = MemoryAtomicStorage()
        LocalNightscoutTlsIdentityRepository(storage).load().clearSecrets()
        storage.corrupt(byteArrayOf(1, 2, 3, 4))
        val generations = AtomicInteger()
        val repository = LocalNightscoutTlsIdentityRepository(storage) {
            generations.incrementAndGet()
            LocalNightscoutTlsIdentityGenerator.generate()
        }

        val first = runCatching(repository::load).exceptionOrNull()
        val second = runCatching(repository::load).exceptionOrNull()

        assertThat(first).isInstanceOf(IllegalStateException::class.java)
        assertThat(first?.message).isEqualTo("Local Nightscout TLS identity is unavailable")
        assertThat(second).isInstanceOf(IllegalStateException::class.java)
        assertThat(generations.get()).isEqualTo(0)
    }

    private fun subjectAlternativeNames(certificate: java.security.cert.X509Certificate): Set<String> =
        certificate.subjectAlternativeNames
            .mapNotNull { entry -> entry.getOrNull(1)?.toString() }
            .toSet()

    private fun validityMs(certificate: java.security.cert.X509Certificate): Long =
        certificate.notAfter.time - certificate.notBefore.time

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class MemoryAtomicStorage : AtomicLocalNightscoutTlsIdentityStorage {
        private var value: ByteArray? = null

        @Synchronized
        override fun readOrCreate(create: () -> ByteArray): ByteArray {
            value?.let { return it.copyOf() }
            val created = create()
            return try {
                value = created.copyOf()
                created.copyOf()
            } finally {
                created.fill(0)
            }
        }

        @Synchronized
        override fun reset() {
            value?.fill(0)
            value = null
        }

        @Synchronized
        fun corrupt(bytes: ByteArray) {
            value?.fill(0)
            value = bytes.copyOf()
        }
    }
}
