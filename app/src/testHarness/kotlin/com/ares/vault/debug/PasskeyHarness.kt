package com.ares.vault.debug

import android.app.Activity
import android.widget.TextView
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Plays the relying party: registers a passkey for "aresvault.test" through Credential Manager,
 * then signs in and verifies the assertion with the returned public key. Writes the outcome to
 * [status] ("created", "verified", or an error) for the instrumented test and for manual checks.
 */
class PasskeyHarness(private val activity: Activity, private val status: TextView) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val manager = CredentialManager.create(activity)
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private var publicKeySpki: ByteArray? = null
    private var credentialId: String? = null

    private fun random(n: Int) = enc.encodeToString(ByteArray(n).also { SecureRandom().nextBytes(it) })

    fun create() = scope.launch {
        val json = """{"rp":{"id":"$RP","name":"AresVault Test"},"user":{"id":"${random(16)}","name":"tester","displayName":"Tester"},
            "challenge":"${random(32)}","pubKeyCredParams":[{"type":"public-key","alg":-7}],
            "authenticatorSelection":{"residentKey":"required","userVerification":"required"},"timeout":60000}"""
        status.text = try {
            val r = manager.createCredential(activity, CreatePublicKeyCredentialRequest(json)) as CreatePublicKeyCredentialResponse
            val root = Json.parseToJsonElement(r.registrationResponseJson).jsonObject
            val resp = root["response"]!!.jsonObject
            publicKeySpki = dec.decode(resp["publicKey"]!!.jsonPrimitive.content)
            credentialId = root["id"]!!.jsonPrimitive.content
            "created"
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun signIn() = scope.launch {
        val challenge = random(32)
        // Like a real relying party: ask for the credential it registered.
        val allow = credentialId?.let { """[{"type":"public-key","id":"$it"}]""" } ?: "[]"
        val json = """{"rpId":"$RP","challenge":"$challenge","userVerification":"required","allowCredentials":$allow}"""
        status.text = try {
            val r = manager.getCredential(activity, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(json))))
            val cred = r.credential as PublicKeyCredential
            val root = Json.parseToJsonElement(cred.authenticationResponseJson).jsonObject
            check(credentialId == null || root["id"]!!.jsonPrimitive.content == credentialId) { "unexpected credential" }
            val resp = root["response"]!!.jsonObject
            val authData = dec.decode(resp["authenticatorData"]!!.jsonPrimitive.content)
            val clientData = dec.decode(resp["clientDataJSON"]!!.jsonPrimitive.content)
            val sig = dec.decode(resp["signature"]!!.jsonPrimitive.content)
            val cd = Json.parseToJsonElement(clientData.decodeToString()).jsonObject
            val sha = MessageDigest.getInstance("SHA-256")
            check(cd["challenge"]!!.jsonPrimitive.content == challenge) { "challenge mismatch" }
            check(cd["type"]!!.jsonPrimitive.content == "webauthn.get") { "wrong type" }
            check(authData.copyOfRange(0, 32).contentEquals(sha.digest(RP.toByteArray()))) { "rpIdHash mismatch" }
            check(authData[32].toInt() and 0x05 == 0x05) { "UP/UV flags missing" }
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeySpki ?: error("create first")))
            val ok = Signature.getInstance("SHA256withECDSA").run {
                initVerify(key); update(authData); update(MessageDigest.getInstance("SHA-256").digest(clientData)); verify(sig)
            }
            if (ok) "verified" else "error: bad signature"
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    companion object {
        const val RP = "aresvault.test"
    }
}
