package com.ares.vault.autofill

import android.content.Context
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.autofill.AutofillId
import android.view.inputmethod.InlineSuggestionsRequest
import com.ares.vault.R
import com.ares.vault.core.autofill.ClientIdentity
import com.ares.vault.core.autofill.FormAnalysis
import com.ares.vault.data.session.SettingsRepository
import com.ares.vault.data.session.VaultSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Everything needed to answer one fill request (also rebuilt inside the auth activity). */
class FillContext(
    val parsed: ParsedStructure,
    val analysis: FormAnalysis<AutofillId>,
    val identity: ClientIdentity,
    val inlineRequest: InlineSuggestionsRequest?,
) {
    val targetLabel: String
        get() = when (val i = identity) {
            is ClientIdentity.Web -> i.host
            is ClientIdentity.App -> i.packageName
        }
}

@Singleton
class FillResponseBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val factory: DatasetFactory,
    private val finder: CandidateFinder,
    private val session: VaultSession,
    private val settings: SettingsRepository,
) {
    /**
     * Login response. [justAuthenticated] = the user unlocked in the auth activity, so
     * datasets carry values directly instead of asking again.
     */
    suspend fun login(ctx: FillContext, justAuthenticated: Boolean = false): FillResponse? {
        val usernameId = ctx.analysis.username
        val passwordId = ctx.analysis.password
        val ids = listOfNotNull(usernameId, passwordId)
        if (ids.isEmpty()) return null
        val inline = InlineSlots(ctx.inlineRequest)
        val datasets = mutableListOf<Dataset>()

        if (session.isUnlocked) {
            val requireAuth = !justAuthenticated && (settings.get()?.requireAuthToFill ?: true)
            for (c in finder.unlocked(ctx.identity).take(MAX_ACCOUNTS)) {
                val title = c.username.ifEmpty { c.title }
                val menu = factory.menu(title, c.title)
                val chip = inline.next { factory.inline(it, title, c.title) }
                datasets += if (requireAuth) {
                    factory.dataset(ids.associateWith { null }, menu, chip, factory.authSender(AutofillAuthActivity.MODE_FILL, c.id))
                } else {
                    val entity = c.entity ?: continue
                    val values = buildMap<AutofillId, String?> {
                        usernameId?.let { put(it, entity.username) }
                        passwordId?.let { put(it, entity.password) }
                    }
                    factory.dataset(values, menu, chip)
                }
            }
        } else {
            val candidates = finder.locked(ctx.identity) ?: return unlockResponse(ctx, ids)
            for (c in candidates.take(MAX_ACCOUNTS)) {
                val title = c.username.ifEmpty { c.title }
                val subtitle = context.getString(R.string.autofill_locked_subtitle, c.title)
                datasets += factory.dataset(
                    ids.associateWith { null },
                    factory.menu(title, subtitle, R.drawable.ic_autofill_lock),
                    inline.next { factory.inline(it, title, c.title, R.drawable.ic_autofill_lock) },
                    factory.authSender(AutofillAuthActivity.MODE_FILL, c.id),
                )
            }
        }

        val searchLabel = context.getString(R.string.autofill_search_vault)
        datasets += factory.dataset(
            ids.associateWith { null },
            factory.menu(searchLabel, null, R.drawable.ic_autofill_search),
            inline.last { factory.inline(it, searchLabel, null, R.drawable.ic_autofill_search, pinned = true) },
            factory.authSender(AutofillAuthActivity.MODE_PICK),
        )
        return FillResponse.Builder().apply { datasets.forEach(::addDataset) }.build()
    }

    /** Locked without an index: a single "Unlock AresVault" entry for the whole response. */
    private fun unlockResponse(ctx: FillContext, ids: List<AutofillId>): FillResponse {
        val label = context.getString(R.string.autofill_unlock)
        val inline = InlineSlots(ctx.inlineRequest)
        return factory.authResponse(
            ids.toTypedArray(),
            factory.authSender(AutofillAuthActivity.MODE_UNLOCK),
            factory.menu(label, null, R.drawable.ic_autofill_lock),
            inline.next { factory.inline(it, label, null, R.drawable.ic_autofill_lock) },
        )
    }

    /** Hands out inline specs in order, reserving the last slot for the pinned search chip. */
    private class InlineSlots(private val request: InlineSuggestionsRequest?) {
        private var used = 0
        private val max = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) request?.maxSuggestionCount ?: 0 else 0

        fun <T> next(build: (android.widget.inline.InlinePresentationSpec) -> T?): T? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || request == null || used >= max - 1) return null
            val specs = request.inlinePresentationSpecs
            return build(specs[minOf(used++, specs.lastIndex)])
        }

        fun <T> last(build: (android.widget.inline.InlinePresentationSpec) -> T?): T? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || request == null || max == 0) return null
            val specs = request.inlinePresentationSpecs
            return build(specs[minOf(used, specs.lastIndex)])
        }
    }

    private companion object {
        const val MAX_ACCOUNTS = 12
    }
}
