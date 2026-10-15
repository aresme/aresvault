package com.ares.vault.autofill

import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import com.ares.vault.BuildConfig
import com.ares.vault.core.autofill.FormType
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
 * requires authentication through [AutofillAuthActivity].
 */
@AndroidEntryPoint
class AresAutofillService : AutofillService() {
    @Inject lateinit var contexts: FillContextFactory
    @Inject lateinit var responses: FillResponseBuilder
    @Inject lateinit var autoLock: AutoLockController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val job = scope.launch {
            try {
                // Respond well inside the framework's ~5 s limit (spec: < 1 s).
                val response = withTimeoutOrNull(RESPONSE_BUDGET_MS) {
                    val structure = request.fillContexts.last().structure
                    val inline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !inlineDisabledForTests()) {
                        request.inlineSuggestionsRequest
                    } else {
                        null
                    }
                    val ctx = contexts.create(structure, inline) ?: return@withTimeoutOrNull null
                    // XOS may have frozen us past the auto-lock deadline: check before reading anything.
                    withContext(Dispatchers.Main) { autoLock.lockIfExpired() }
                    when (ctx.analysis.type) {
                        FormType.LOGIN -> responses.login(ctx)
                        else -> null // sign-up arrives in Phase 5
                    }
                }
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
        callback.onSuccess() // Phase 5
    }

    override fun onConnected() {
        AresLog.d(COMPONENT, "Autofill service connected")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Debug/test builds only: lets the instrumented test force dropdown suggestions. */
    private fun inlineDisabledForTests(): Boolean =
        BuildConfig.DEBUG && getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).getBoolean(DEBUG_DISABLE_INLINE, false)

    companion object {
        private const val COMPONENT = "Autofill"
        private const val RESPONSE_BUDGET_MS = 1500L
        const val DEBUG_PREFS = "debug_autofill"
        const val DEBUG_DISABLE_INLINE = "disable_inline"
    }
}
