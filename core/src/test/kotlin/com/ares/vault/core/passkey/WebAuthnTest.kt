package com.ares.vault.core.passkey

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

class WebAuthnTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** RFC 8949 Appendix A examples. */
    @Test fun cborVectors() {
        assertThat(hex(Cbor.encode(Cbor.int(0)))).isEqualTo("00")
        assertThat(hex(Cbor.encode(Cbor.int(23)))).isEqualTo("17")
        assertThat(hex(Cbor.encode(Cbor.int(24)))).isEqualTo("1818")
        assertThat(hex(Cbor.encode(Cbor.int(1000)))).isEqualTo("1903e8")
        assertThat(hex(Cbor.encode(Cbor.int(1000000)))).isEqualTo("1a000f4240")
        assertThat(hex(Cbor.encode(Cbor.int(1000000000000L)))).isEqualTo("1b000000e8d4a51000")
        assertThat(hex(Cbor.encode(Cbor.int(-1)))).isEqualTo("20")
        assertThat(hex(Cbor.encode(Cbor.int(-100)))).isEqualTo("3863")
        assertThat(hex(Cbor.encode(Cbor.Text("IETF")))).isEqualTo("6449455446")
        assertThat(hex(Cbor.encode(Cbor.Bytes(byteArrayOf(1, 2, 3, 4))))).isEqualTo("4401020304")
        assertThat(hex(Cbor.encode(Cbor.MapOf(emptyList())))).isEqualTo("a0")
        assertThat(hex(Cbor.encode(Cbor.MapOf(listOf(Cbor.int(1) to Cbor.int(2), Cbor.int(3) to Cbor.int(4)))))).isEqualTo("a201020304")
    }

    @Test fun registrationStructureAndAssertionVerify() {
        val kp = WebAuthn.newKeyPair()
        val pub = kp.public as ECPublicKey
        val credId = ByteArray(16) { it.toByte() }
        val authData = WebAuthn.authenticatorData("example.com", 0, credId to pub)
        // rpIdHash(32) flags(1) count(4) aaguid(16) len(2) credId(16) cose(77)
        assertThat(authData.copyOfRange(0, 32)).isEqualTo(WebAuthn.sha256("example.com".toByteArray()))
        assertThat(authData[32].toInt() and 0xFF).isEqualTo(0x45) // UP | UV | AT
        assertThat(authData.copyOfRange(37, 53)).isEqualTo(WebAuthn.AAGUID)
        assertThat(authData[54].toInt()).isEqualTo(16)
        val cose = authData.copyOfRange(55 + 16, authData.size)
        assertThat(cose.size).isEqualTo(77)
        assertThat(hex(cose.copyOfRange(0, 10))).isEqualTo("a5010203262001215820")
        assertThat(hex(WebAuthn.attestationObject(byteArrayOf(9)).copyOfRange(0, 20))).isEqualTo("a363666d74646e6f6e656761747453746d74a068")

        // Assertion: a verifier with only the public key accepts the signature.
        val clientData = WebAuthn.clientDataJson("webauthn.get", "Y2hhbGxlbmdl", "https://example.com").toByteArray()
        val assertAuth = WebAuthn.authenticatorData("example.com", 1)
        assertThat(assertAuth.size).isEqualTo(37)
        val sig = WebAuthn.sign(kp.private, assertAuth, WebAuthn.sha256(clientData))
        val verifierKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub.encoded))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(verifierKey); update(assertAuth); update(WebAuthn.sha256(clientData)); verify(sig)
        }
        assertThat(ok).isTrue()
        // Same key works after a PKCS#8 round trip (how it's stored in the vault).
        val restored = WebAuthn.privateKey(kp.private.encoded)
        val sig2 = WebAuthn.sign(restored, assertAuth, WebAuthn.sha256(clientData))
        val ok2 = Signature.getInstance("SHA256withECDSA").run { initVerify(verifierKey); update(assertAuth); update(WebAuthn.sha256(clientData)); verify(sig2) }
        assertThat(ok2).isTrue()
    }

    @Test fun rpIdMustMatchOrigin() {
        WebAuthn.validateRpIdForOrigin("example.com", "https://example.com")
        WebAuthn.validateRpIdForOrigin("example.com", "https://login.example.com")
        WebAuthn.validateRpIdForOrigin("example.co.ke", "https://www.example.co.ke")
        listOf(
            "example.com" to "https://evil.com",
            "example.com" to "https://example.com.evil.com",
            "example.com" to "http://example.com",
            "co.ke" to "https://example.co.ke",
            "github.io" to "https://alice.github.io",
        ).forEach { (rp, origin) -> assertThrows(PasskeyException::class.java) { WebAuthn.validateRpIdForOrigin(rp, origin) } }
    }

    @Test fun parsesCredentialManagerJson() {
        val create = WebAuthn.parseCreation(
            """{"rp":{"id":"webauthn.io","name":"WebAuthn.io"},"user":{"id":"dXNlcg","name":"kip","displayName":"Kip"},
               "challenge":"abc","pubKeyCredParams":[{"type":"public-key","alg":-8},{"type":"public-key","alg":-7}],
               "excludeCredentials":[{"id":"AAEC","type":"public-key"}]}""",
        )
        assertThat(create.rpId).isEqualTo("webauthn.io")
        assertThat(create.userId.decodeToString()).isEqualTo("user")
        assertThat(create.excludeCredentialIds).containsExactly("AAEC")
        assertThrows(PasskeyException::class.java) {
            WebAuthn.parseCreation("""{"rp":{"id":"x.com"},"user":{"id":"dQ"},"challenge":"c","pubKeyCredParams":[{"alg":-257}]}""")
        }
        val get = WebAuthn.parseRequest("""{"rpId":"webauthn.io","challenge":"xyz","allowCredentials":[]}""")
        assertThat(get.rpId).isEqualTo("webauthn.io")
    }
}
