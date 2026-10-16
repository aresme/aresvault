package com.ares.vault.core.passkey

import com.ares.vault.core.autofill.PublicSuffixList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigInteger
import java.net.URI
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class PasskeyException(message: String) : Exception(message)

/** A stored passkey's public parts plus the PKCS#8 private key (kept in the encrypted DB). */
class PasskeyMaterial(val credentialId: ByteArray, val privateKeyPkcs8: ByteArray, val publicKey: ECPublicKey)

/**
 * WebAuthn Level 3 authenticator operations for ES256 passkeys (spec §9 follow-up).
 * Key generation and ECDSA come from the platform JCA. This object only assembles the
 * byte structures the WebAuthn spec defines (authenticatorData, COSE key, attestation).
 */
object WebAuthn {
    const val ES256 = -7

    /** Fixed AAGUID identifying AresVault passkeys (random, generated once for this app). */
    val AAGUID: ByteArray = hex("a7e5b1c2" + "4d3f" + "4e8a" + "9b61" + "2c0f7d93e4a5")

    private const val FLAG_UP = 0x01
    private const val FLAG_UV = 0x04
    private const val FLAG_AT = 0x40

    private val b64url = Base64.getUrlEncoder().withoutPadding()
    private val b64urlDecoder = Base64.getUrlDecoder()

    fun b64(bytes: ByteArray): String = b64url.encodeToString(bytes)
    fun unb64(s: String): ByteArray = b64urlDecoder.decode(s.trimEnd('='))

    fun newKeyPair(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun privateKey(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    /** COSE_Key for an EC2 P-256 ES256 public key, in CTAP2 canonical key order. */
    fun coseKey(pub: ECPublicKey): ByteArray = Cbor.encode(
        Cbor.MapOf(
            listOf(
                Cbor.int(1) to Cbor.int(2), // kty: EC2
                Cbor.int(3) to Cbor.int(ES256), // alg
                Cbor.int(-1) to Cbor.int(1), // crv: P-256
                Cbor.int(-2) to Cbor.Bytes(fixed32(pub.w.affineX)),
                Cbor.int(-3) to Cbor.Bytes(fixed32(pub.w.affineY)),
            ),
        ),
    )

    /** authenticatorData = rpIdHash ‖ flags ‖ signCount ‖ [attestedCredentialData]. */
    fun authenticatorData(rpId: String, signCount: Long, attested: Pair<ByteArray, ECPublicKey>? = null): ByteArray {
        val flags = FLAG_UP or FLAG_UV or (if (attested != null) FLAG_AT else 0)
        val head = sha256(rpId.toByteArray()) + byteArrayOf(flags.toByte()) + ByteBuffer.allocate(4).putInt(signCount.toInt()).array()
        if (attested == null) return head
        val (credId, pub) = attested
        return head + AAGUID + byteArrayOf((credId.size shr 8).toByte(), credId.size.toByte()) + credId + coseKey(pub)
    }

    /** "none" attestation object: {fmt, attStmt, authData} in canonical key order. */
    fun attestationObject(authData: ByteArray): ByteArray = Cbor.encode(
        Cbor.MapOf(
            listOf(
                Cbor.Text("fmt") to Cbor.Text("none"),
                Cbor.Text("attStmt") to Cbor.MapOf(emptyList()),
                Cbor.Text("authData") to Cbor.Bytes(authData),
            ),
        ),
    )

    /** ES256 signature (ASN.1 DER, as WebAuthn expects) over authenticatorData ‖ clientDataHash. */
    fun sign(privateKey: PrivateKey, authData: ByteArray, clientDataHash: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(authData)
            update(clientDataHash)
            sign()
        }

    fun clientDataJson(type: String, challengeB64: String, origin: String, packageName: String? = null): String =
        buildJsonObject {
            put("type", type)
            put("challenge", challengeB64)
            put("origin", origin)
            put("crossOrigin", false)
            packageName?.let { put("androidPackageName", it) }
        }.toString()

    /**
     * WebAuthn rpId rule: the rpId must be the origin's host or a registrable parent of it,
     * over https, and never a public suffix (e.g. "co.ke" or "github.io").
     */
    fun validateRpIdForOrigin(rpId: String, origin: String, psl: PublicSuffixList = PublicSuffixList.default) {
        val uri = try {
            URI(origin)
        } catch (_: Exception) {
            throw PasskeyException("Invalid origin")
        }
        val host = uri.host?.lowercase() ?: throw PasskeyException("Invalid origin")
        if (uri.scheme != "https" && host != "localhost") throw PasskeyException("Passkeys need a secure (https) site")
        val rp = rpId.lowercase()
        if (host != rp && !host.endsWith(".$rp")) throw PasskeyException("This site can't use passkeys for $rpId")
        if (psl.registrableDomain(rp) == null && rp != "localhost") throw PasskeyException("$rpId is not a valid passkey domain")
    }

    // ---- Request/response JSON (WebAuthn JSON encoding used by Credential Manager) ----

    data class CreationOptions(
        val rpId: String, val rpName: String, val userId: ByteArray, val userName: String, val userDisplayName: String,
        val challenge: String, val excludeCredentialIds: List<String>,
    )

    data class RequestOptions(val rpId: String, val challenge: String, val allowCredentialIds: List<String>)

    private val json = Json { ignoreUnknownKeys = true }

    fun parseCreation(requestJson: String): CreationOptions {
        val o = json.parseToJsonElement(requestJson).jsonObject
        val rp = o["rp"]?.jsonObject ?: throw PasskeyException("Missing rp")
        val user = o["user"]?.jsonObject ?: throw PasskeyException("Missing user")
        val algs = o["pubKeyCredParams"]?.jsonArray?.mapNotNull { it.jsonObject["alg"]?.jsonPrimitive?.int }.orEmpty()
        if (algs.isNotEmpty() && ES256 !in algs) throw PasskeyException("This site doesn't accept the ES256 passkeys AresVault creates")
        return CreationOptions(
            rpId = rp["id"]?.jsonPrimitive?.content ?: throw PasskeyException("Missing rp.id"),
            rpName = rp["name"]?.jsonPrimitive?.content.orEmpty(),
            userId = unb64(user["id"]?.jsonPrimitive?.content ?: throw PasskeyException("Missing user.id")),
            userName = user["name"]?.jsonPrimitive?.content.orEmpty(),
            userDisplayName = user["displayName"]?.jsonPrimitive?.content.orEmpty(),
            challenge = o["challenge"]?.jsonPrimitive?.content ?: throw PasskeyException("Missing challenge"),
            excludeCredentialIds = o["excludeCredentials"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }.orEmpty(),
        )
    }

    fun parseRequest(requestJson: String): RequestOptions {
        val o = json.parseToJsonElement(requestJson).jsonObject
        return RequestOptions(
            rpId = o["rpId"]?.jsonPrimitive?.content ?: throw PasskeyException("Missing rpId"),
            challenge = o["challenge"]?.jsonPrimitive?.content ?: throw PasskeyException("Missing challenge"),
            allowCredentialIds = o["allowCredentials"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }.orEmpty(),
        )
    }

    fun registrationResponseJson(credentialId: ByteArray, clientDataJson: ByteArray, authData: ByteArray, publicKey: ECPublicKey): String =
        buildJsonObject {
            put("id", b64(credentialId))
            put("rawId", b64(credentialId))
            put("type", "public-key")
            put("authenticatorAttachment", "platform")
            putJsonObject("response") {
                put("clientDataJSON", b64(clientDataJson))
                put("attestationObject", b64(attestationObject(authData)))
                put("authenticatorData", b64(authData))
                put("publicKey", b64(publicKey.encoded)) // SubjectPublicKeyInfo DER
                put("publicKeyAlgorithm", ES256)
                put("transports", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("internal"))))
            }
            putJsonObject("clientExtensionResults") { putJsonObject("credProps") { put("rk", true) } }
        }.toString()

    fun assertionResponseJson(credentialId: ByteArray, clientDataJson: ByteArray, authData: ByteArray, signature: ByteArray, userHandle: ByteArray): String =
        buildJsonObject {
            put("id", b64(credentialId))
            put("rawId", b64(credentialId))
            put("type", "public-key")
            put("authenticatorAttachment", "platform")
            putJsonObject("response") {
                put("clientDataJSON", b64(clientDataJson))
                put("authenticatorData", b64(authData))
                put("signature", b64(signature))
                put("userHandle", b64(userHandle))
            }
            put("clientExtensionResults", JsonObject(emptyMap()))
        }.toString()

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /** Big-endian, exactly 32 bytes (strip BigInteger sign byte / left-pad). */
    private fun fixed32(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
