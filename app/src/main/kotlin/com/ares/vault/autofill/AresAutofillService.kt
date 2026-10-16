package com.ares.vault.autofill

import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.widget.Toast
import com.ares.vault.core.autofill.BrowserAllowlist
import com.ares.vault.core.autofill.FormType
import com.ares.vault.core.autofill.PublicSuffixList
import com.ares.vault.data.AresLog
import com.ares.vault.data.session.AutoLockController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * System-wide autofill (spec §6.3). Responses never contain secrets unless the vault is
 * unlocked *and* the user disabled "ask before filling"; otherwise every account dataset
 * requires authentication through [AutofillAuthActivity]. Sign-up forms get a "Generate"
 * dataset; every response asks the system to offer "Save to AresVault?" on submit.
 */
@AndroidEntryPoint
class AresAutofillService : AutofillService() {
    @Inject lateinit var contexts: FillContextFactory
    @Inject lateinit var responses: FillResponseBuilder
    @Inject lateinit var autoLock: AutoLockController
    @Inject lateinit var saver: SaveHandler
    @Inject lateinit var pendingSaves: PendingSaveStore
    @Inject lateinit var factory: DatasetFactory

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val job = scope.launch {
            try {
                // Respond well inside the framework's ~5 s limit (spec: < 1 s).
                val response = withTimeoutOrNull(RESPONSE_BUDGET_MS) {
                    val structure = request.fillContexts.last().structure
                    val inline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        request.inlineSuggestionsRequest
                    } else {
                        null
                    }
                    val ctx = contexts.create(structure, inline) ?: return@withTimeoutOrNull null
                    AresLog.d(COMPONENT, "Fill request for ${ctx.parsed.packageName}: ${ctx.analysis.type}")
                    // XOS may have frozen us past the auto-lock deadline: check before reading anything.
                    withContext(Dispatchers.Main) { autoLock.lockIfExpired() }
                    when (ctx.analysis.type) {
                        FormType.LOGIN -> responses.login(ctx)
                        FormType.SIGN_UP -> responses.signUp(ctx)
                        FormType.NONE -> null
                    }
                }
                if (response == null) AresLog.d(COMPONENT, "No response (nothing to offer or over time budget)")
                callback.onSuccess(response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AresLog.e(COMPONENT, "Fill request failed", e)
                callback.onSuccess(null)
            }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        scope.launch {
            try {
                val save = saver.extract(request)
                if (save == null) {
                    callback.onSuccess()
                    return@launch
                }
                when (val outcome = saver.handle(save, pendingSaves)) {
                    is SaveHandler.Outcome.Saved -> {
                        toast("Saved “${outcome.title}” to AresVault")
                        callback.onSuccess()
                    }
                    is SaveHandler.Outcome.NeedsUi -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        callback.onSuccess(factory.saveSender(outcome.token))
                    } else {
                        startActivity(
                            Intent(this@AresAutofillService, SaveActivity::class.java)
                                .putExtra(SaveActivity.EXTRA_TOKEN, outcome.token)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        callback.onSuccess()
                    }
                    SaveHandler.Outcome.Ignored -> callback.onSuccess()
                }
            } catch (e: Exception) {
                AresLog.e(COMPONENT, "Save request failed", e)
                callback.onFailure("AresVault couldn't save this login")
            }
        }
    }

    private suspend fun toast(text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(this@AresAutofillService, text, Toast.LENGTH_SHORT).show()
    }

    override fun onConnected() {
        AresLog.d(COMPONENT, "Autofill service connected")
        // Warm the bundled lists off the request path so the first fill isn't slow.
        scope.launch {
            PublicSuffixList.default
            BrowserAllowlist.default
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val COMPONENT = "Autofill"
        /** The framework gives up after ~5 s; cold starts on a frozen XOS process need headroom. */
        private const val RESPONSE_BUDGET_MS = 3000L
    }
}
