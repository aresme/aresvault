package com.ares.vault.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ares.vault.data.keystore.BiometricKeyStore
import com.ares.vault.data.session.UnlockResult
import com.ares.vault.data.session.VaultManager
import com.ares.vault.prefs.AppPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

data class LockUiState(
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val lockoutRemainingMillis: Long = 0,
    val failedAttempts: Int = 0,
    val biometricAvailable: Boolean = false,
    val biometricInvalidated: Boolean = false,
) {
    val lockedOut: Boolean get() = lockoutRemainingMillis > 0
}

@HiltViewModel
class LockViewModel @Inject constructor(
    private val manager: VaultManager,
    private val prefs: AppPrefs,
) : ViewModel() {
    private val _ui = MutableStateFlow(LockUiState(biometricAvailable = manager.hasBiometricSlot()))
    val ui: StateFlow<LockUiState> = _ui.asStateFlow()
    private var ticker: Job? = null

    /** Show the biometric prompt automatically once per lock-screen visit. */
    var autoPromptDone = false

    init {
        _ui.update { it.copy(failedAttempts = manager.failedAttempts()) }
        startTicker(manager.lockoutRemainingMillis())
    }

    fun onPassword(value: String) = _ui.update { it.copy(password = value, error = null) }

    fun unlock() {
        val s = _ui.value
        if (s.busy || s.lockedOut || s.password.isEmpty()) return
        val pw = s.password.toCharArray()
        _ui.update { it.copy(busy = true, password = "", error = null) }
        viewModelScope.launch {
            val result = try {
                manager.unlockWithPassword(pw)
            } finally {
                pw.fill('\u0000')
            }
            val attempts = manager.failedAttempts()
            _ui.update { it.copy(busy = false, failedAttempts = attempts) }
            when (result) {
                UnlockResult.Success -> Unit // AppStateViewModel swaps the screen
                UnlockResult.WrongPassword -> _ui.update {
                    val left = 5 - attempts
                    it.copy(error = "Wrong master password" + if (left in 1..2) " ($left tries before a delay)" else "")
                }
                is UnlockResult.LockedOut -> {
                    _ui.update { it.copy(error = "Too many wrong attempts") }
                    startTicker(result.remainingMillis)
                }
                UnlockResult.NoVault -> _ui.update { it.copy(error = "No vault found") }
            }
        }
    }

    /** Null when fingerprint can't be used right now (state explains why). */
    fun biometricCipher(): Cipher? = when (val r = manager.biometricUnlockCipher()) {
        is BiometricKeyStore.CipherResult.Ready -> r.cipher
        BiometricKeyStore.CipherResult.Invalidated -> {
            _ui.update {
                it.copy(
                    biometricAvailable = false,
                    biometricInvalidated = true,
                    error = "Fingerprints changed on this phone. Unlock with your master password to re-enable fingerprint unlock.",
                )
            }
            null
        }
        BiometricKeyStore.CipherResult.Missing -> {
            _ui.update { it.copy(biometricAvailable = false) }
            null
        }
    }

    fun unlockWithBiometric(cipher: Cipher) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = manager.unlockWithBiometric(cipher)
            _ui.update {
                it.copy(busy = false, error = if (r == UnlockResult.Success) null else "Fingerprint unlock failed — use your master password")
            }
        }
    }

    fun biometricError(message: String) = _ui.update { it.copy(error = message) }

    val biometricWanted: Boolean get() = prefs.biometricWanted

    private fun startTicker(initial: Long) {
        ticker?.cancel()
        if (initial <= 0) {
            _ui.update { it.copy(lockoutRemainingMillis = 0) }
            return
        }
        ticker = viewModelScope.launch {
            var remaining = initial
            while (remaining > 0) {
                _ui.update { it.copy(lockoutRemainingMillis = remaining) }
                delay(minOf(1000, remaining))
                remaining = manager.lockoutRemainingMillis()
            }
            _ui.update { it.copy(lockoutRemainingMillis = 0, error = null) }
        }
    }
}
