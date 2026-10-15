package com.ares.vault.ui.onboarding

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ares.vault.core.crypto.SecureBytes
import com.ares.vault.core.policy.MasterPasswordPolicy
import com.ares.vault.data.session.VaultManager
import com.ares.vault.prefs.AppPrefs
import com.ares.vault.ui.BiometricEnroller
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val manager: VaultManager,
    private val prefs: AppPrefs,
    private val enroller: BiometricEnroller,
) : ViewModel() {

    enum class Step { WELCOME, PASSWORD, CREATING, FINGERPRINT }

    data class UiState(
        val step: Step = Step.WELCOME,
        val password: String = "",
        val confirm: String = "",
        val evaluation: MasterPasswordPolicy.Evaluation? = null,
        val error: String? = null,
        val createdInfo: String? = null,
        val fingerprintMessage: String? = null,
    )

    private val policy = MasterPasswordPolicy()
    private val _ui = MutableStateFlow(UiState(step = if (manager.vaultExists()) Step.FINGERPRINT else Step.WELCOME))
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var evalJob: Job? = null

    fun start() = _ui.update { it.copy(step = Step.PASSWORD) }

    fun onPassword(value: String) {
        _ui.update { it.copy(password = value, error = null) }
        evaluate()
    }

    fun onConfirm(value: String) {
        _ui.update { it.copy(confirm = value, error = null) }
        evaluate()
    }

    private fun evaluate() {
        evalJob?.cancel()
        evalJob = viewModelScope.launch {
            delay(150) // debounce zxcvbn while typing
            val s = _ui.value
            val pw = s.password.toCharArray()
            val confirm = s.confirm.toCharArray()
            val e = withContext(Dispatchers.Default) { policy.evaluate(pw, confirm) }
            SecureBytes.wipe(pw)
            SecureBytes.wipe(confirm)
            _ui.update { it.copy(evaluation = e) }
        }
    }

    fun create() {
        val s = _ui.value
        val pw = s.password.toCharArray()
        val confirm = s.confirm.toCharArray()
        val e = policy.evaluate(pw, confirm)
        SecureBytes.wipe(confirm)
        if (!e.acceptable) {
            SecureBytes.wipe(pw)
            _ui.update { it.copy(evaluation = e, error = "Choose a stronger password that matches its confirmation") }
            return
        }
        _ui.update { it.copy(step = Step.CREATING, password = "", confirm = "") }
        viewModelScope.launch {
            try {
                val r = manager.create(pw)
                _ui.update {
                    it.copy(
                        step = Step.FINGERPRINT,
                        createdInfo = "Key derivation tuned for this phone: ${r.params.iterations} passes × " +
                            "${r.params.memoryKiB / 1024} MiB (${r.measuredMillis} ms)",
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(step = Step.PASSWORD, error = "Could not create vault: ${e.message}") }
            } finally {
                SecureBytes.wipe(pw)
            }
        }
    }

    fun biometricAvailability(context: Context) = enroller.availability(context)

    suspend fun enableFingerprint(activity: FragmentActivity) {
        when (val r = enroller.enable(activity)) {
            BiometricEnroller.Result.Enabled -> finish()
            BiometricEnroller.Result.Cancelled -> Unit
            is BiometricEnroller.Result.Failed -> _ui.update { it.copy(fingerprintMessage = r.message) }
        }
    }

    fun finish() = prefs.setOnboardingDone(true)
}
