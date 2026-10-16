package com.ares.vault.passkey

import android.content.Context
import android.content.Intent
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ares.vault.autofill.UnlockController
import com.ares.vault.core.passkey.PasskeyException
import com.ares.vault.core.passkey.WebAuthn
import com.ares.vault.data.AresLog
import com.ares.vault.data.db.PasskeyEntity
import com.ares.vault.data.repo.PasskeyRepository
import com.ares.vault.data.session.VaultManager
import com.ares.vault.data.session.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.UUID
import javax.inject.Inject

/** One passkey operation requested by Credential Manager. */
sealed interface PasskeyTask {
    class Create(val requestJson: String, val clientDataHash: ByteArray?, val caller: PasskeyCaller) : PasskeyTask
    class Get(val requestJson: String, val clientDataHash: ByteArray?, val caller: PasskeyCaller, val passkeyId: String) : PasskeyTask
    class Unlock(val request: BeginGetCredentialRequest) : PasskeyTask
}

/**
 * unlock-or-verify → check caller (browser origin vs rpId, or the user's per-app approval)
 * → create/sign → hand the result back through [PendingIntentHandler].
 */
@HiltViewModel
class PasskeyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    manager: VaultManager,
    private val session: VaultSession,
    private val passkeys: PasskeyRepository,
    private val entries: PasskeyEntries,
    private val presence: UserVerification,
) : ViewModel() {

    sealed interface Step {
        data object Starting : Step
        data object Unlocking : Step
        /** Vault already open: confirm presence (WebAuthn user verification). */
        class Confirm(val title: String, val subtitle: String) : Step
        class ApproveApp(val appLabel: String, val packageName: String, val rpId: String, val creating: Boolean) : Step
        class Done(val result: Intent, val message: String? = null) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Starting)
    val step: StateFlow<Step> = _step.asStateFlow()
    val unlock = UnlockController(manager, viewModelScope, onSuccess = { afterUserVerified() }, onCancel = { cancel() })

    private lateinit var task: PasskeyTask

    fun start(task: PasskeyTask, canConfirm: Boolean) {
        if (this::task.isInitialized) return
        this.task = task
        when {
            !session.isUnlocked -> {
                _step.value = Step.Unlocking
                unlock.begin()
            }
            task is PasskeyTask.Unlock -> viewModelScope.launch { afterUserVerified() }
            // Just unlocked via Credential Manager's "Unlock AresVault" step: don't ask again.
            presence.consume() -> viewModelScope.launch { afterUserVerified() }
            canConfirm -> _step.value = Step.Confirm(
                if (task is PasskeyTask.Create) "Create a passkey" else "Sign in with a passkey",
                rpIdOrEmpty(),
            )
            else -> {
                _step.value = Step.Unlocking
                unlock.beginVerify()
            }
        }
    }

    fun onConfirmed() = viewModelScope.launch { afterUserVerified() }

    fun confirmFailed(message: String?) {
        _step.value = Step.Unlocking
        unlock.beginVerify(message)
    }

    /** The user unlocked or confirmed: user verification (UV) done. */
    private suspend fun afterUserVerified() {
        // An unlock that only *lists* passkeys lends its verification to the next step.
        if (task is PasskeyTask.Unlock) presence.markVerified()
        try {
            when (val t = task) {
                is PasskeyTask.Unlock -> {
                    val response = entries.build(context, t.request)
                    finish(Intent().also { PendingIntentHandler.setBeginGetCredentialResponse(it, response) })
                }
                is PasskeyTask.Create -> checkCallerThen(t.caller, WebAuthn.parseCreation(t.requestJson).rpId, creating = true) { create(t) }
                is PasskeyTask.Get -> checkCallerThen(t.caller, WebAuthn.parseRequest(t.requestJson).rpId, creating = false) { get(t) }
            }
        } catch (e: PasskeyException) {
            fail(e.message ?: "Passkey request refused")
        }
    }

    private var pendingApproved: (suspend () -> Unit)? = null

    private suspend fun checkCallerThen(caller: PasskeyCaller, rpId: String, creating: Boolean, action: suspend () -> Unit) {
        when (caller) {
            is PasskeyCaller.Browser -> {
                WebAuthn.validateRpIdForOrigin(rpId, caller.origin)
                action()
            }
            is PasskeyCaller.App -> {
                if (com.ares.vault.core.autofill.PublicSuffixList.default.registrableDomain(rpId) == null) {
                    throw PasskeyException("$rpId is not a valid passkey domain")
                }
                if (passkeys.isAppLinked(rpId, caller.packageName, caller.certSha256)) {
                    action()
                } else {
                    pendingApproved = action
                    _step.value = Step.ApproveApp(PasskeyCaller.label(context.packageManager, caller.packageName), caller.packageName, rpId, creating)
                }
            }
        }
    }

    fun approveApp() {
        val t = task
        val s = _step.value as? Step.ApproveApp ?: return
        val caller = when (t) {
            is PasskeyTask.Create -> t.caller
            is PasskeyTask.Get -> t.caller
            is PasskeyTask.Unlock -> return
        } as? PasskeyCaller.App ?: return
        viewModelScope.launch {
            passkeys.linkApp(s.rpId, caller.packageName, caller.certSha256)
            AresLog.i("Passkey", "User approved ${caller.packageName} for ${s.rpId}")
            try {
                pendingApproved?.invoke()
            } catch (e: PasskeyException) {
                fail(e.message ?: "Passkey request refused")
            }
        }
    }

    private suspend fun create(t: PasskeyTask.Create) = withContext(Dispatchers.Default) {
        val o = WebAuthn.parseCreation(t.requestJson)
        val rpId = o.rpId.lowercase()
        if (o.excludeCredentialIds.any { passkeys.byCredentialId(it) != null }) {
            val intent = Intent()
            PendingIntentHandler.setCreateCredentialException(intent, CreatePublicKeyCredentialDomException(InvalidStateError(), "You already have a passkey for this account in AresVault"))
            finish(intent)
            return@withContext
        }
        val kp = WebAuthn.newKeyPair()
        val pub = kp.public as ECPublicKey
        val credId = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val authData = WebAuthn.authenticatorData(rpId, 0, credId to pub)
        val clientData = WebAuthn.clientDataJson("webauthn.create", o.challenge, t.caller.origin, (t.caller as? PasskeyCaller.App)?.packageName).toByteArray()
        passkeys.insert(
            PasskeyEntity(
                id = UUID.randomUUID().toString(), credentialId = WebAuthn.b64(credId), rpId = rpId, rpName = o.rpName,
                userHandle = WebAuthn.b64(o.userId), userName = o.userName, userDisplayName = o.userDisplayName,
                privateKey = Base64.getEncoder().encodeToString(kp.private.encoded), createdAt = System.currentTimeMillis(),
            ),
        )
        AresLog.i("Passkey", "Created passkey for $rpId")
        val json = WebAuthn.registrationResponseJson(credId, clientData, authData, pub)
        finish(Intent().also { PendingIntentHandler.setCreateCredentialResponse(it, CreatePublicKeyCredentialResponse(json)) }, "Passkey saved in AresVault")
    }

    private suspend fun get(t: PasskeyTask.Get) = withContext(Dispatchers.Default) {
        val o = WebAuthn.parseRequest(t.requestJson)
        val p = passkeys.get(t.passkeyId) ?: throw PasskeyException("Passkey not found")
        if (!p.rpId.equals(o.rpId, ignoreCase = true)) throw PasskeyException("Passkey belongs to a different site")
        val count = p.signCount + 1
        val authData = WebAuthn.authenticatorData(p.rpId, count)
        val clientData = WebAuthn.clientDataJson("webauthn.get", o.challenge, t.caller.origin, (t.caller as? PasskeyCaller.App)?.packageName).toByteArray()
        // A browser hashes its own clientDataJSON; otherwise sign the hash of ours.
        val hash = t.clientDataHash ?: WebAuthn.sha256(clientData)
        val signature = WebAuthn.sign(WebAuthn.privateKey(Base64.getDecoder().decode(p.privateKey)), authData, hash)
        passkeys.markUsed(p.id, count)
        val json = WebAuthn.assertionResponseJson(WebAuthn.unb64(p.credentialId), clientData, authData, signature, WebAuthn.unb64(p.userHandle))
        finish(Intent().also { PendingIntentHandler.setGetCredentialResponse(it, GetCredentialResponse(PublicKeyCredential(json))) })
    }

    fun cancel() {
        val intent = Intent()
        when (task) {
            is PasskeyTask.Create -> PendingIntentHandler.setCreateCredentialException(intent, CreateCredentialCancellationException("Cancelled"))
            else -> PendingIntentHandler.setGetCredentialException(intent, GetCredentialCancellationException("Cancelled"))
        }
        finish(intent)
    }

    private fun fail(message: String) {
        AresLog.w("Passkey", "Refused: $message")
        val intent = Intent()
        when (task) {
            is PasskeyTask.Create -> PendingIntentHandler.setCreateCredentialException(intent, CreateCredentialUnknownException(message))
            else -> PendingIntentHandler.setGetCredentialException(intent, GetCredentialUnknownException(message))
        }
        finish(intent, message)
    }

    private fun finish(intent: Intent, message: String? = null) {
        _step.value = Step.Done(intent, message)
    }

    private fun rpIdOrEmpty(): String = runCatching {
        when (val t = task) {
            is PasskeyTask.Create -> WebAuthn.parseCreation(t.requestJson).rpId
            is PasskeyTask.Get -> WebAuthn.parseRequest(t.requestJson).rpId
            is PasskeyTask.Unlock -> ""
        }
    }.getOrDefault("")
}
