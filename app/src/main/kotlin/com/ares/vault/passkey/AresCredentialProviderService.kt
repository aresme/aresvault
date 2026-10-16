package com.ares.vault.passkey

import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import com.ares.vault.data.AresLog
import com.ares.vault.data.session.AutoLockController
import com.ares.vault.data.session.VaultSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Passkey provider for Android's Credential Manager (Android 14+). The "begin" calls only list
 * options; every real operation happens in [PasskeyActivity] after user verification.
 */
@AndroidEntryPoint
class AresCredentialProviderService : CredentialProviderService() {
    @Inject lateinit var entries: PasskeyEntries
    @Inject lateinit var session: VaultSession
    @Inject lateinit var autoLock: AutoLockController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            callback.onError(CreateCredentialUnknownException("AresVault only stores passkeys here"))
            return
        }
        val entry = CreateEntry.Builder("AresVault", entries.intent(this, PasskeyActivity.MODE_CREATE))
            .setDescription("Save the passkey in AresVault on this phone")
            .build()
        callback.onResult(BeginCreateCredentialResponse(createEntries = listOf(entry)))
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        if (request.beginGetCredentialOptions.none { it is BeginGetPublicKeyCredentialOption }) {
            callback.onResult(BeginGetCredentialResponse())
            return
        }
        val job = scope.launch {
            try {
                withContext(Dispatchers.Main) { autoLock.lockIfExpired() }
                val response = if (session.isUnlocked) {
                    entries.build(this@AresCredentialProviderService, request)
                } else {
                    // Locked: list nothing, offer "Unlock AresVault"; the activity returns the entries.
                    BeginGetCredentialResponse.Builder()
                        .addAuthenticationAction(AuthenticationAction.Builder("Unlock AresVault", entries.intent(this@AresCredentialProviderService, PasskeyActivity.MODE_UNLOCK)).build())
                        .build()
                }
                callback.onResult(response)
            } catch (e: Exception) {
                AresLog.e("Passkey", "Begin get failed", e)
                callback.onError(GetCredentialUnknownException(e.message))
            }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        callback.onResult(null)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
