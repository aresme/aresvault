package com.ares.vault.autofill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ares.vault.core.autofill.ClientIdentity
import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.data.session.VaultManager
import com.ares.vault.data.session.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SaveViewModel @Inject constructor(
    manager: VaultManager,
    private val session: VaultSession,
    private val store: PendingSaveStore,
    private val handler: SaveHandler,
) : ViewModel() {

    sealed interface Step {
        data object Starting : Step
        data object Unlocking : Step
        class AskUpdate(val existing: CredentialEntity) : Step
        /** Finished; [message] is shown as a toast (null = silent). */
        class Done(val message: String?) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Starting)
    val step: StateFlow<Step> = _step.asStateFlow()
    val unlock = UnlockController(manager, viewModelScope, onSuccess = { decide() }, onCancel = { cancel() })

    private var save: PendingSave? = null

    val target: String
        get() = when (val i = save?.identity) {
            is ClientIdentity.Web -> i.host
            is ClientIdentity.App -> i.packageName
            null -> ""
        }
    val username: String get() = save?.username.orEmpty()

    fun start(token: String?) {
        if (save != null || _step.value !is Step.Starting) return
        save = token?.let(store::take)
        if (save == null) {
            _step.value = Step.Done(null)
            return
        }
        if (session.isUnlocked) {
            viewModelScope.launch { decide() }
        } else {
            _step.value = Step.Unlocking
            unlock.begin()
        }
    }

    private suspend fun decide() {
        val s = save ?: return
        _step.value = when (val d = handler.decide(s)) {
            SaveDecision.AlreadySaved -> Step.Done("Already saved in AresVault")
            SaveDecision.New -> Step.Done("Saved “${handler.create(s)}” to AresVault")
            is SaveDecision.Update -> Step.AskUpdate(d.existing)
        }
    }

    fun update(existing: CredentialEntity) = finishWith {
        handler.update(existing, it)
        "Password updated"
    }

    fun saveAsNew() = finishWith { "Saved “${handler.create(it)}” to AresVault" }

    fun cancel() {
        save = null
        _step.value = Step.Done(null)
    }

    private fun finishWith(action: suspend (PendingSave) -> String) {
        val s = save ?: return
        viewModelScope.launch {
            _step.value = Step.Done(action(s))
            save = null
        }
    }

    override fun onCleared() {
        save = null
    }
}
