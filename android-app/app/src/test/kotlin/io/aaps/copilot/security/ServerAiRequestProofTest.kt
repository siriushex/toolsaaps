package io.aaps.copilot.security

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.io.File

class ServerAiRequestProofTest {
    private fun message(credential: String = "test-token", path: String = "/api/ai/v1/jobs") =
        ServerAiRequestProof.signingBytes("POST", path, "{}".toByteArray(), credential,
            1800000000000L, "11111111-1111-4111-8111-111111111111")

    @Test fun canonicalOrderAndHashesMatchServerContract() {
        val text = message().toString(Charsets.US_ASCII)
        assertTrue(text.startsWith("{\"aud\":\"https://diai.centv.ru\",\"body_sha256\":"))
        assertTrue(text.contains("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"))
        assertFalse(text.contains("test-token"))
        assertTrue(text.endsWith("\"path\":\"/api/ai/v1/jobs\",\"v\":1}"))
        assertArrayEquals(message(), message())
    }

    @Test fun signatureIsVerifiableAndBoundToCredential() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keys = generator.generateKeyPair()
        val signature = ServerAiRequestProof.sign(keys.private, message())
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(keys.public)
        verifier.update(message())
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(signature)))
        verifier.initVerify(keys.public)
        verifier.update(message("other-token"))
        assertFalse(verifier.verify(Base64.getUrlDecoder().decode(signature)))
        System.getenv("COPILOT_PROOF_FIXTURE")?.let { destination ->
            File(destination).apply { parentFile?.mkdirs() }.writeText(
                """{"public_key_der":"${Base64.getEncoder().encodeToString(keys.public.encoded)}","signature":"$signature","message_b64":"${Base64.getEncoder().encodeToString(message())}"}"""
            )
        }
    }

    @Test fun rejectsNonCanonicalPathsAndNonAsciiCredentials() {
        assertThrows(IllegalArgumentException::class.java) { message(path = "/api/ai/v1/jobs?token=secret") }
        assertThrows(IllegalArgumentException::class.java) { message(credential = "\u00e9") }
    }
}
