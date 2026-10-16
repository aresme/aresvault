package com.ares.vault.autofill

import android.os.Parcelable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ares.vault.core.autofill.ClientIdentity
import com.ares.vault.core.autofill.StoredIdentity
import com.ares.vault.core.domain.Domains
import com.ares.vault.data.AresLog
import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.data.repo.CredentialRepository
import com.ares.vault.data.repo.toDraft
import com.ares.vault.data.session.SettingsRepository
import com.ares.vault.data.session.VaultManager
import com.ares.vault.data.session.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives [AutofillAuthActivity]: unlock (via [UnlockController]) or confirm presence, then
 * produce the dataset or response for the system.
 */
@HiltViewModel
class AutofillAuthViewModel @Inject constructor(
    private val manager: VaultManager,
    private val session: VaultSession,
    private val repository: CredentialRepository,
    private val settings: SettingsRepository,
    private val responses: FillResponseBuilder,
    private val factory: DatasetFactory,
    private val finder: CandidateFinder,
) : ViewModel() {

    sealed interface Step {
        data object Starting : Step
        /** Unlocking or re-entering the master password; see [unlock]. */
        data object Unlocking : Step
        /** Vault unlocked; confirm the user is present (fingerprint or screen lock). */
        data object Confirm : Step
        class Pick(val items: List<CredentialEntity>) : Step
        class Done(val result: Parcelable?) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Starting)
    val step: StateFlow<Step> = _step.asStateFlow()

    val unlock = UnlockController(manager, viewModelScope, onSuccess = { proceed() }, onCancel = { cancel() })

    private lateinit var ctx: FillContext
    private lateinit var mode: String
    private var credentialId: String? = null

    val target: String get() = ctx.targetLabel

    fun start(ctx: FillContext, mode: String, credentialId: String?, canConfirm: Boolean) {
        if (this::ctx.isInitialized) return
        this.ctx = ctx
        this.mode = mode
        this.credentialId = credentialId
        viewModelScope.launch {
            when {
                !session.isUnlocked -> {
                    _step.value = Step.Unlocking
                    unlock.begin()
                }
                mode != AutofillAuthActivity.MODE_UNLOCK && (settings.get()?.requireAuthToFill ?: true) ->
                    if (canConfirm) _step.value = Step.Confirm else verifyWithPassword()
                else -> proceed()
            }
        }
    }

    fun onConfirmed() = viewModelScope.launch { proceed() }

    /** Presence check failed or unavailable: fall back to re-entering the master password. */
    fun verifyWithPassword(message: String? = null) {
        _step.value = Step.Unlocking
        unlock.beginVerify(message)
    }

    /** Authenticated: build what the system asked for. */
    private suspend fun proceed() {
        when (mode) {
            AutofillAuthActivity.MODE_FILL -> {
                val entity = credentialId?.let { repository.get(it) }
                // Re-check the match against the identity derived from the *current* structure.
                if (entity == null || !finder.matches(entity.storedIdentity(), ctx.identity, entity.id)) {
                    AresLog.w("Autofill", "Credential no longer matches requester; refusing to fill")
                    finishWith(null)
                    return
                }
                finishWith(datasetFor(entity))
            }
            AutofillAuthActivity.MODE_UNLOCK -> finishWith(responses.login(ctx, justAuthenticated = true))
            AutofillAuthActivity.MODE_PICK -> _step.value = Step.Pick(repository.all().sortedBy { it.title.lowercase() })
        }
    }

    /** Whether picking [entity] would also link it to the requesting app/site. */
    fun canLink(entity: CredentialEntity): Boolean = !finder.matches(entity.storedIdentity(), ctx.identity) &&
        when (val i = ctx.identity) {
            is ClientIdentity.App -> entity.appPackage == null && i.certSha256 != null
            is ClientIdentity.Web -> true
        }

    fun linkLabel(): String = when (val i = ctx.identity) {
        is ClientIdentity.App -> "Remember for this app"
        is ClientIdentity.Web -> "Remember for ${i.host}"
    }

    fun pick(entity: CredentialEntity, link: Boolean) {
        viewModelScope.launch {
            if (link && canLink(entity)) {
                val draft = entity.toDraft()
                val updated = when (val i = ctx.identity) {
                    is ClientIdentity.App -> draft.copy(appPackage = i.packageName, appCertSha256 = i.certSha256)
                    is ClientIdentity.Web ->
                        if (draft.webDomain == null) draft.copy(webDomain = i.host) else draft.copy(extraUrls = draft.extraUrls + i.host)
                }
                repository.update(entity.id, updated)
                AresLog.i("Autofill", "Linked entry to ${ctx.targetLabel}")
            }
            finishWith(datasetFor(entity))
        }
    }

    fun cancel() {
        _step.value = Step.Done(null)
    }

    private suspend fun datasetFor(entity: CredentialEntity): Parcelable? {
        val values = buildMap {
            ctx.analysis.username?.let { put(it, entity.username) }
            ctx.analysis.password?.let { put(it, entity.password) }
        }
        if (values.isEmpty()) return null
        repository.markUsed(entity.id)
        return factory.authResult(values)
    }

    private fun finishWith(result: Parcelable?) {
        _step.value = Step.Done(result)
    }
}

fun CredentialEntity.storedIdentity() = StoredIdentity(appPackage, appCertSha256, webDomain, extraUrls)

/** Normalised host list of an entry, for display. */
fun CredentialEntity.sites(): String =
    (listOfNotNull(webDomain, appPackage) + extraUrls.mapNotNull { Domains.normalizeHost(it) }).distinct().joinToString(", ")
