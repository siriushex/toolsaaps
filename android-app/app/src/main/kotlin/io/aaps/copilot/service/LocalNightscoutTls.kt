package io.aaps.copilot.service

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLServerSocketFactory

object LocalNightscoutTls {
    const val KEYSTORE_ASSET = "local_ns_keystore.p12"
    private const val KEYSTORE_TYPE = "PKCS12"
    private const val KEYSTORE_PASSWORD = "copilotlocal"
    private const val KEY_ALIAS = "localns"

    fun createServerSocketFactory(context: Context): SSLServerSocketFactory {
        val keyStore = loadKeyStore(context)
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, KEYSTORE_PASSWORD.toCharArray())
        return NanoHTTPD.makeSSLSocketFactory(keyStore, keyManagerFactory)
    }

    fun loadServerCertificate(context: Context): X509Certificate {
        val keyStore = loadKeyStore(context)
        val certificate = keyStore.getCertificate(KEY_ALIAS)
            ?: error("Local TLS certificate alias not found: $KEY_ALIAS")
        return certificate as? X509Certificate
            ?: error("Local TLS certificate is not X509")
    }

    fun loadCaCertificate(context: Context): X509Certificate {
        val keyStore = loadKeyStore(context)
        val chain = keyStore.getCertificateChain(KEY_ALIAS)
            ?: error("Local TLS certificate chain alias not found: $KEY_ALIAS")
        val ca = chain.lastOrNull()
            ?: error("Local TLS certificate chain is empty: $KEY_ALIAS")
        return ca as? X509Certificate
            ?: error("Local TLS CA certificate is not X509")
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

    private fun loadKeyStore(context: Context): KeyStore {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        context.assets.open(KEYSTORE_ASSET).use { input ->
            keyStore.load(input, KEYSTORE_PASSWORD.toCharArray())
        }
        return keyStore
    }
}
