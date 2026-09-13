package io.aaps.copilot.service

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import okhttp3.tls.HeldCertificate

internal class LocalNightscoutTlsIdentityRecord(
    val pkcs12Bytes: ByteArray,
    val passwordBytes: ByteArray,
    val apiSecretBytes: ByteArray
) {
    fun clear() {
        pkcs12Bytes.fill(0)
        passwordBytes.fill(0)
        apiSecretBytes.fill(0)
    }
}

internal class LocalNightscoutTlsLoadedIdentity(
    val keyStore: KeyStore,
    val password: CharArray,
    val apiSecretBytes: ByteArray,
    val serverCertificate: X509Certificate,
    val caCertificate: X509Certificate
) {
    fun clearSecrets() {
        password.fill('\u0000')
        apiSecretBytes.fill(0)
    }

    fun clearPassword() = clearSecrets()
}

internal object LocalNightscoutTlsIdentityGenerator {
    internal val CA_VALIDITY_MS = TimeUnit.DAYS.toMillis(3650)
    internal val SERVER_VALIDITY_MS = TimeUnit.DAYS.toMillis(3650)
    private const val CLOCK_SKEW_MS = 5L * 60L * 1000L
    private const val RANDOM_PASSWORD_BYTES = 32
    private const val RANDOM_API_SECRET_BYTES = 32
    private const val SERIAL_BYTES = 16
    private val secureRandom = SecureRandom()
    private val secretAlphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            .toByteArray(Charsets.US_ASCII)

    fun generate(): LocalNightscoutTlsIdentityRecord {
        val notBefore = System.currentTimeMillis() - CLOCK_SKEW_MS
        val notAfter = notBefore + CA_VALIDITY_MS
        val caSerial = randomSerial()
        var serverSerial = randomSerial()
        while (serverSerial == caSerial) serverSerial = randomSerial()

        val ca = HeldCertificate.Builder()
            .commonName("AAPS Predictive Copilot Local Nightscout CA")
            .serialNumber(caSerial)
            .validityInterval(notBefore, notAfter)
            .certificateAuthority(0)
            .build()
        val server = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .serialNumber(serverSerial)
            .validityInterval(notBefore, notBefore + SERVER_VALIDITY_MS)
            .signedBy(ca)
            .build()
        val passwordBytes = randomAsciiBytes(RANDOM_PASSWORD_BYTES)
        val apiSecretBytes = randomAsciiBytes(RANDOM_API_SECRET_BYTES)
        val passwordChars = asciiChars(passwordBytes)
        val output = WipeableByteArrayOutputStream()
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null, null) }
            keyStore.setKeyEntry(
                LocalNightscoutTls.KEY_ALIAS,
                server.keyPair.private,
                passwordChars,
                arrayOf(server.certificate, ca.certificate)
            )
            keyStore.store(output, passwordChars)
            return LocalNightscoutTlsIdentityRecord(
                pkcs12Bytes = output.copyBytes(),
                passwordBytes = passwordBytes,
                apiSecretBytes = apiSecretBytes
            )
        } catch (failure: Throwable) {
            passwordBytes.fill(0)
            apiSecretBytes.fill(0)
            throw failure
        } finally {
            passwordChars.fill('\u0000')
            output.wipe()
        }
    }

    private fun randomAsciiBytes(size: Int): ByteArray = ByteArray(size).also { bytes ->
        bytes.indices.forEach { index ->
            bytes[index] = secretAlphabet[secureRandom.nextInt(secretAlphabet.size)]
        }
    }

    private fun randomSerial(): BigInteger {
        val bytes = ByteArray(SERIAL_BYTES).also(secureRandom::nextBytes)
        return try {
            BigInteger(1, bytes).max(BigInteger.ONE)
        } finally {
            bytes.fill(0)
        }
    }
}

internal object LocalNightscoutTlsIdentityRecordCodec {
    private const val MAGIC = 0x4c4e5449
    private const val VERSION = 2
    private const val MAX_PASSWORD_BYTES = 256
    private const val MAX_API_SECRET_BYTES = 256
    private const val MAX_PKCS12_BYTES = 256 * 1024

    fun encode(record: LocalNightscoutTlsIdentityRecord): ByteArray {
        check(record.passwordBytes.size in 1..MAX_PASSWORD_BYTES)
        check(record.apiSecretBytes.size in 1..MAX_API_SECRET_BYTES)
        check(record.pkcs12Bytes.size in 1..MAX_PKCS12_BYTES)
        val output = WipeableByteArrayOutputStream()
        try {
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeInt(record.passwordBytes.size)
                data.write(record.passwordBytes)
                data.writeInt(record.apiSecretBytes.size)
                data.write(record.apiSecretBytes)
                data.writeInt(record.pkcs12Bytes.size)
                data.write(record.pkcs12Bytes)
            }
            return output.copyBytes()
        } finally {
            output.wipe()
        }
    }

    fun decode(encoded: ByteArray): LocalNightscoutTlsIdentityRecord {
        var passwordBytes = ByteArray(0)
        var apiSecretBytes = ByteArray(0)
        var pkcs12Bytes = ByteArray(0)
        try {
            DataInputStream(ByteArrayInputStream(encoded)).use { data ->
                check(data.readInt() == MAGIC) { "TLS identity record marker is invalid" }
                check(data.readInt() == VERSION) { "TLS identity record version is unsupported" }
                passwordBytes = readBounded(data, MAX_PASSWORD_BYTES, "password")
                apiSecretBytes = readBounded(data, MAX_API_SECRET_BYTES, "API secret")
                pkcs12Bytes = readBounded(data, MAX_PKCS12_BYTES, "container")
                check(data.read() == -1) { "TLS identity record has trailing data" }
            }
            return LocalNightscoutTlsIdentityRecord(
                pkcs12Bytes = pkcs12Bytes,
                passwordBytes = passwordBytes,
                apiSecretBytes = apiSecretBytes
            )
        } catch (failure: Throwable) {
            passwordBytes.fill(0)
            apiSecretBytes.fill(0)
            pkcs12Bytes.fill(0)
            throw failure
        }
    }

    private fun readBounded(data: DataInputStream, max: Int, label: String): ByteArray {
        val length = data.readInt()
        check(length in 1..max) { "TLS identity $label length is invalid" }
        return ByteArray(length).also(data::readFully)
    }
}

internal object LocalNightscoutTlsIdentityLoader {
    fun load(record: LocalNightscoutTlsIdentityRecord): LocalNightscoutTlsLoadedIdentity {
        val password = asciiChars(record.passwordBytes)
        val apiSecret = record.apiSecretBytes.copyOf()
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
            ByteArrayInputStream(record.pkcs12Bytes).use { input ->
                keyStore.load(input, password)
            }
            val aliases = keyStore.aliases().toList()
            check(aliases == listOf(LocalNightscoutTls.KEY_ALIAS)) {
                "TLS identity alias set is invalid"
            }
            check(keyStore.getKey(LocalNightscoutTls.KEY_ALIAS, password) is PrivateKey) {
                "TLS identity private key is unavailable"
            }
            val chain = keyStore.getCertificateChain(LocalNightscoutTls.KEY_ALIAS)
                ?: error("TLS identity chain is unavailable")
            check(chain.size == 2) { "TLS identity chain length is invalid" }
            val server = chain[0] as? X509Certificate
                ?: error("TLS server certificate is invalid")
            val ca = chain[1] as? X509Certificate
                ?: error("TLS CA certificate is invalid")
            validateCertificates(server, ca)
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .init(keyStore, password)
            return LocalNightscoutTlsLoadedIdentity(
                keyStore = keyStore,
                password = password,
                apiSecretBytes = apiSecret,
                serverCertificate = server,
                caCertificate = ca
            )
        } catch (failure: Exception) {
            password.fill('\u0000')
            apiSecret.fill(0)
            throw failure
        }
    }

    private fun validateCertificates(server: X509Certificate, ca: X509Certificate) {
        val now = Date()
        server.checkValidity(now)
        ca.checkValidity(now)
        check(server.basicConstraints == -1) { "TLS server certificate is a CA" }
        check(ca.basicConstraints >= 0) { "TLS CA basic constraints are invalid" }
        check(server.issuerX500Principal == ca.subjectX500Principal) {
            "TLS certificate issuer is invalid"
        }
        check(ca.issuerX500Principal == ca.subjectX500Principal) {
            "TLS CA is not self-issued"
        }
        server.verify(ca.publicKey)
        ca.verify(ca.publicKey)
        check(server.serialNumber > BigInteger.ZERO && ca.serialNumber > BigInteger.ZERO) {
            "TLS certificate serial is invalid"
        }
        check(server.serialNumber != ca.serialNumber) {
            "TLS certificate serials are not distinct"
        }
        check(
            server.notAfter.time - server.notBefore.time <=
                LocalNightscoutTlsIdentityGenerator.SERVER_VALIDITY_MS
        ) { "TLS server certificate validity is too long" }
        check(
            ca.notAfter.time - ca.notBefore.time <=
                LocalNightscoutTlsIdentityGenerator.CA_VALIDITY_MS
        ) { "TLS CA certificate validity is too long" }
        val sans = server.subjectAlternativeNames
            ?.mapNotNull { entry -> entry.getOrNull(1)?.toString() }
            ?.toSet()
            .orEmpty()
        check("localhost" in sans && "127.0.0.1" in sans) {
            "TLS server certificate loopback SANs are missing"
        }
    }
}

internal interface AtomicLocalNightscoutTlsIdentityStorage {
    fun readOrCreate(create: () -> ByteArray): ByteArray
    fun reset()
}

internal class KeystoreLocalNightscoutTlsIdentityStorage(
    private val storage: AtomicLocalNightscoutTlsIdentityStorage
) : AtomicLocalNightscoutTlsIdentityStorage {
    constructor(context: Context) : this(LocalNightscoutEncryptedIdentityStorage(context))

    override fun readOrCreate(create: () -> ByteArray): ByteArray = storage.readOrCreate(create)

    override fun reset() = storage.reset()
}

internal class LocalNightscoutTlsIdentityRepository(
    private val storage: AtomicLocalNightscoutTlsIdentityStorage,
    private val generator: () -> LocalNightscoutTlsIdentityRecord =
        LocalNightscoutTlsIdentityGenerator::generate
) {
    fun load(): LocalNightscoutTlsLoadedIdentity = try {
        val encoded = storage.readOrCreate {
            val generated = generator()
            try {
                LocalNightscoutTlsIdentityRecordCodec.encode(generated)
            } finally {
                generated.clear()
            }
        }
        try {
            val record = LocalNightscoutTlsIdentityRecordCodec.decode(encoded)
            try {
                LocalNightscoutTlsIdentityLoader.load(record)
            } finally {
                record.clear()
            }
        } finally {
            encoded.fill(0)
        }
    } catch (failure: Exception) {
        throw IllegalStateException(FAILURE_MESSAGE, failure)
    }

    fun reset() = storage.reset()

    private companion object {
        const val FAILURE_MESSAGE = "Local Nightscout TLS identity is unavailable"
    }
}

private fun asciiChars(bytes: ByteArray): CharArray =
    CharArray(bytes.size) { index -> (bytes[index].toInt() and 0xff).toChar() }

private class WipeableByteArrayOutputStream : ByteArrayOutputStream() {
    fun copyBytes(): ByteArray = toByteArray()

    fun wipe() {
        buf.fill(0)
        reset()
    }
}

private const val KEYSTORE_TYPE = "PKCS12"
