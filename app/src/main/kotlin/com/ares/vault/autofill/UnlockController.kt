package com.ares.vault.autofill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.ares.vault.data.keystore.BiometricKeyStore
import com.ares.vault.data.session.UnlockResult
import com.ares.vault.data.session.VaultManager
import com.ares.vault.ui.BiometricAuthenticator
import com.ares.vault.ui.components.PasswordField
import com.ares.vault.ui.components.formatCountdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.Cipher

/**
 * Unlock flow shared by the autofill activities: fingerprint (Keystore CryptoObject) first,
 * master password as fallback. With [verifyOnly] the password is just re-checked (vault
 * already open) — used as the presence check when no screen lock exists.
 */
class UnlockController(
    private val manager: VaultManager,
    private val scope: CoroutineScope,
    private val onSuccess: suspend () -> Unit,
    private val onCancel: () -> Unit,
) {
    sealed interface Step {
        data object Idle : Step
        class Biometric(val cipher: Cipher) : Step
        data object Password : Step
    }

    data class State(
        val step: Step = Step.Idle,
        val password: String = "",
        val error: String? = null,
        val busy: Boolean = false,
        val verifyOnly: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Starts unlocking: fingerprint if enrolled and valid, else the password sheet. */
    fun begin() {
        when (val r = manager.biometricUnlockCipher()) {
            is BiometricKeyStore.CipherResult.Ready -> _state.update { it.copy(step = Step.Biometric(r.cipher)) }
            BiometricKeyStore.CipherResult.Invalidated -> usePassword("Fingerprints changed — enter your master password")
            BiometricKeyStore.CipherResult.Missing -> usePassword()
        }
    }

    /** Password sheet that only verifies (vault already unlocked). */
    fun beginVerify(message: String? = null) {
        _state.update { it.copy(verifyOnly = true) }
        usePassword(message)
    }

    fun usePassword(message: String? = null) = _state.update { it.copy(step = Step.Password, error = message) }

    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onBiometricSuccess(cipher: Cipher) {
        _state.update { it.copy(busy = true) }
        scope.launch {
            val ok = manager.unlockWithBiometric(cipher) == UnlockResult.Success
            _state.update { it.copy(busy = false, step = if (ok) Step.Idle else Step.Password, error = if (ok) null else "Fingerprint unlock failed") }
            if (ok) onSuccess()
        }
    }

    fun submit() {
        val s = _state.value
        val pw = s.password.toCharArray()
        if (pw.isEmpty() || s.busy) return
        _state.update { it.copy(password = "", busy = true) }
        scope.launch {
            val r = try {
                if (s.verifyOnly) manager.verifyMasterPassword(pw) else manager.unlockWithPassword(pw)
            } finally {
                pw.fill('\u0000')
            }
            _state.update { it.copy(busy = false) }
            when (r) {
                UnlockResult.Success -> {
                    _state.update { it.copy(step = Step.Idle) }
                    onSuccess()
                }
                UnlockResult.WrongPassword -> _state.update { it.copy(error = "Wrong master password") }
                is UnlockResult.LockedOut -> _state.update {
                    it.copy(error = "Too many attempts. Try again in ${formatCountdown(r.remainingMillis)}")
                }
                UnlockResult.NoVault -> onCancel()
            }
        }
    }

    fun cancel() = onCancel()
}

/** Biometric prompt side effect for [UnlockController.Step.Biometric]. */
@Composable
fun BiometricUnlockEffect(controller: UnlockController, step: UnlockController.Step.Biometric, activity: FragmentActivity, subtitle: String) {
    LaunchedEffect(step) {
        when (val r = BiometricAuthenticator.authenticate(activity, step.cipher, "Unlock AresVault", subtitle)) {
            is BiometricAuthenticator.Result.Success -> controller.onBiometricSuccess(r.cipher)
            is BiometricAuthenticator.Result.Error -> controller.usePassword(r.message)
            else -> controller.usePassword()
        }
    }
}

/** Master-password sheet content for [UnlockController.Step.Password]. */
@Composable
fun ColumnScope.PasswordUnlockContent(state: UnlockController.State, controller: UnlockController, purpose: String) {
    Text(if (state.verifyOnly) "Confirm it's you" else "Unlock AresVault", style = MaterialTheme.typography.titleLarge)
    Text(purpose, color = MaterialTheme.colorScheme.onSurfaceVariant)
    PasswordField(state.password, controller::onPassword, "Master password", isError = state.error != null, onImeAction = controller::submit)
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = controller::cancel) { Text("Cancel") }
        Button(onClick = controller::submit, enabled = !state.busy && state.password.isNotEmpty()) {
            Text(if (state.verifyOnly) "Confirm" else "Unlock")
        }
    }
}
