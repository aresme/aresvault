package com.ares.vault.passkey

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ares.vault.autofill.AutofillSheet
import com.ares.vault.autofill.BiometricUnlockEffect
import com.ares.vault.autofill.PasswordUnlockContent
import com.ares.vault.autofill.UnlockController
import com.ares.vault.autofill.secureAutofillWindow
import com.ares.vault.core.passkey.PasskeyException
import com.ares.vault.ui.BiometricAuthenticator
import com.ares.vault.ui.theme.AresVaultTheme
import dagger.hilt.android.AndroidEntryPoint

/** Credential Manager hands passkey create/get/unlock requests to this activity. */
@AndroidEntryPoint
class PasskeyActivity : FragmentActivity() {
    private val vm: PasskeyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureAutofillWindow()
        val task = try {
            readTask()
        } catch (e: PasskeyException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            null
        }
        if (task == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        vm.start(task, BiometricAuthenticator.canConfirm(this))
        setContent {
            AresVaultTheme {
                val step by vm.step.collectAsStateWithLifecycle()
                val unlock by vm.unlock.state.collectAsStateWithLifecycle()
                when (val s = step) {
                    PasskeyViewModel.Step.Starting -> Unit
                    PasskeyViewModel.Step.Unlocking -> when (val u = unlock.step) {
                        is UnlockController.Step.Biometric -> BiometricUnlockEffect(vm.unlock, u, this, "Use your passkey")
                        UnlockController.Step.Password -> AutofillSheet(vm::cancel) { PasswordUnlockContent(unlock, vm.unlock, "to use your passkey") }
                        UnlockController.Step.Idle -> Unit
                    }
                    is PasskeyViewModel.Step.Confirm -> LaunchedEffect(s) {
                        when (val r = BiometricAuthenticator.confirm(this@PasskeyActivity, s.title, s.subtitle)) {
                            BiometricAuthenticator.Result.Confirmed -> vm.onConfirmed()
                            is BiometricAuthenticator.Result.Error -> vm.confirmFailed(r.message)
                            else -> vm.cancel()
                        }
                    }
                    is PasskeyViewModel.Step.ApproveApp -> AutofillSheet(vm::cancel) {
                        Text("Allow ${s.appLabel}?", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${s.appLabel} (${s.packageName}) wants to ${if (s.creating) "create" else "use"} a passkey for ${s.rpId}.\n\n" +
                                "Android normally checks with ${s.rpId} that this app is allowed to — AresVault has no internet " +
                                "access, so it can't. Only allow this if it is the official ${s.rpId} app you installed yourself. " +
                                "AresVault remembers this app and its signature.",
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = vm::cancel) { Text("Don't allow") }
                            Button(onClick = vm::approveApp) { Text("Allow") }
                        }
                    }
                    is PasskeyViewModel.Step.Done -> LaunchedEffect(s) {
                        s.message?.let { Toast.makeText(this@PasskeyActivity, it, Toast.LENGTH_SHORT).show() }
                        setResult(RESULT_OK, s.result)
                        finish()
                    }
                }
            }
        }
    }

    private fun readTask(): PasskeyTask? = when (intent.getStringExtra(EXTRA_MODE)) {
        MODE_CREATE -> {
            val req = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: return null
            val call = req.callingRequest as? CreatePublicKeyCredentialRequest ?: return null
            PasskeyTask.Create(call.requestJson, call.clientDataHash, PasskeyCaller.resolve(req.callingAppInfo))
        }
        MODE_GET -> {
            val req = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) ?: return null
            val option = req.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().firstOrNull() ?: return null
            val id = intent.getStringExtra(EXTRA_PASSKEY_ID) ?: return null
            PasskeyTask.Get(option.requestJson, option.clientDataHash, PasskeyCaller.resolve(req.callingAppInfo), id)
        }
        MODE_UNLOCK -> PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)?.let { PasskeyTask.Unlock(it) }
        else -> null
    }

    companion object {
        const val EXTRA_MODE = "com.ares.vault.passkey.MODE"
        const val EXTRA_PASSKEY_ID = "com.ares.vault.passkey.ID"
        const val MODE_CREATE = "create"
        const val MODE_GET = "get"
        const val MODE_UNLOCK = "unlock"
    }
}
