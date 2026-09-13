package io.aaps.copilot.service

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.security.cert.X509Certificate
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

internal class LocalNightscoutTlsServerMaterial(
    val socketFactory: SSLServerSocketFactory,
    val caCertificate: X509Certificate,
    val serverCertificate: X509Certificate,
    val authenticator: LocalNightscoutApiAuthenticator
) {
    fun clearAuthenticator() = authenticator.clear()
}

internal class LocalNightscoutClientIdentity(
    val caCertificate: X509Certificate,
    val apiSecretSha1: String
)

object LocalNightscoutTls {
    internal const val KEY_ALIAS = "localns"

    internal fun loadClientIdentity(context: Context): LocalNightscoutClientIdentity {
        val identity = loadIdentity(context)
        return try {
            LocalNightscoutClientIdentity(
                identity.caCertificate,
                MessageDigest.getInstance("SHA-1").digest(identity.apiSecretBytes)
                    .joinToString("") { "%02x".format(it) }
            )
        } finally {
            identity.clearSecrets()
        }
    }

    fun createServerSocketFactory(context: Context): SSLServerSocketFactory {
        val identity = loadIdentity(context)
        return try {
            val keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(identity.keyStore, identity.password)
            NanoHTTPD.makeSSLSocketFactory(identity.keyStore, keyManagerFactory)
        } finally {
            identity.clearPassword()
        }
    }

    internal fun createServerMaterial(context: Context): LocalNightscoutTlsServerMaterial {
        val identity = loadIdentity(context)
        var authenticator: LocalNightscoutApiAuthenticator? = null
        return try {
            authenticator = LocalNightscoutApiAuthenticator.fromRawSecret(identity.apiSecretBytes)
            val keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(identity.keyStore, identity.password)
            LocalNightscoutTlsServerMaterial(
                socketFactory = NanoHTTPD.makeSSLSocketFactory(identity.keyStore, keyManagerFactory),
                caCertificate = identity.caCertificate,
                serverCertificate = identity.serverCertificate,
                authenticator = authenticator
            )
        } catch (failure: Throwable) {
            authenticator?.clear()
            throw failure
        } finally {
            identity.clearSecrets()
        }
    }

    fun loadServerCertificate(context: Context): X509Certificate {
        val identity = loadIdentity(context)
        return try {
            identity.serverCertificate
        } finally {
            identity.clearPassword()
        }
    }

    fun loadCaCertificate(context: Context): X509Certificate {
        val identity = loadIdentity(context)
        return try {
            identity.caCertificate
        } finally {
            identity.clearPassword()
        }
    }

    fun toPem(certificate: X509Certificate): String {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(certificate.encoded)
        return buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            append(base64)
            append('\n')
            append("-----END CERTIFICATE-----\n")
        }
    }

    fun caFingerprintSha256(context: Context): String =
        fingerprintSha256(loadCaCertificate(context))

    internal fun fingerprintSha256(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }

    internal fun <T> withApiSecretChars(context: Context, block: (CharArray) -> T): T {
        val identity = loadIdentity(context)
        val chars = CharArray(identity.apiSecretBytes.size) { index ->
            (identity.apiSecretBytes[index].toInt() and 0xff).toChar()
        }
        return try {
            block(chars)
        } finally {
            chars.fill('\u0000')
            identity.clearSecrets()
        }
    }

    fun resetIdentity(context: Context) {
        LocalNightscoutTlsIdentityRepository(
            KeystoreLocalNightscoutTlsIdentityStorage(context.applicationContext)
        ).reset()
    }

    internal fun pinnedSelfHandshake(
        port: Int,
        material: LocalNightscoutTlsServerMaterial
    ) {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("localns-ca", material.caCertificate)
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(trustStore) }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustManagerFactory.trustManagers, null)
        }
        val socket = sslContext.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
        try {
            socket.soTimeout = SELF_HANDSHAKE_TIMEOUT_MS
            socket.sslParameters = socket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            socket.startHandshake()
            val peer = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: error("Pinned Local Nightscout peer certificate is missing")
            check(MessageDigest.isEqual(peer.encoded, material.serverCertificate.encoded)) {
                "Pinned Local Nightscout peer certificate changed"
            }
        } finally {
            socket.close()
        }
    }

    private fun loadIdentity(context: Context): LocalNightscoutTlsLoadedIdentity =
        LocalNightscoutTlsIdentityRepository(
            KeystoreLocalNightscoutTlsIdentityStorage(context.applicationContext)
        ).load()

    private const val SELF_HANDSHAKE_TIMEOUT_MS = 5_000
}
