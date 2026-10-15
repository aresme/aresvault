package com.ares.vault.ui.settings

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ares.vault.core.crypto.SecureBytes
import com.ares.vault.core.generator.GeneratorSettings
import com.ares.vault.core.generator.Preset
import com.ares.vault.core.policy.MasterPasswordPolicy
import com.ares.vault.data.db.SettingsEntity
import com.ares.vault.data.session.SettingsRepository
import com.ares.vault.data.session.UnlockResult
import com.ares.vault.data.session.VaultManager
import com.ares.vault.data.settings.GeneratorSettingsRepository
import com.ares.vault.ui.BiometricEnroller
import com.ares.vault.ui.components.formatCountdown
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val generatorRepo: GeneratorSettingsRepository,
    private val manager: VaultManager,
    private val enroller: BiometricEnroller,
) : ViewModel() {
    val settings: StateFlow<SettingsEntity?> =
        settingsRepo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val generator = MutableStateFlow(GeneratorSettings())
    val biometricEnabled = MutableStateFlow(manager.hasBiometricSlot())
    val message = MutableStateFlow<String?>(null)
    val kdfInfo: String = manager.currentParams()?.let {
        "Argon2id · ${it.memoryKiB / 1024} MiB · ${it.iterations} passes · ${it.parallelism} lane"
    }.orEmpty()

    init {
        viewModelScope.launch { generator.value = generatorRepo.load() }
    }

    fun update(transform: (SettingsEntity) -> SettingsEntity) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    fun setDefaultPreset(preset: Preset) {
        val updated = generator.value.copy(defaultPreset = preset)
        generator.value = updated
        viewModelScope.launch { generatorRepo.save(updated) }
    }

    fun biometricAvailability(context: Context) = enroller.availability(context)

    suspend fun setBiometric(activity: FragmentActivity, enable: Boolean) {
        if (enable) {
            when (val r = enroller.enable(activity)) {
                BiometricEnroller.Result.Enabled -> message.value = "Fingerprint unlock enabled"
                BiometricEnroller.Result.Cancelled -> Unit
                is BiometricEnroller.Result.Failed -> message.value = r.message
            }
        } else {
            enroller.disable()
            message.value = "Fingerprint unlock disabled"
        }
        biometricEnabled.value = manager.hasBiometricSlot()
    }

    /** Returns null on success, otherwise an error to show in the dialog. */
    suspend fun changePassword(current: String, new: String, confirm: String): String? {
        val newChars = new.toCharArray()
        val confirmChars = confirm.toCharArray()
        val currentChars = current.toCharArray()
        try {
            val eval = withContext(Dispatchers.Default) { MasterPasswordPolicy().evaluate(newChars, confirmChars) }
            if (!eval.matches) return "New passwords don't match"
            if (!eval.longEnough) return "Use at least ${MasterPasswordPolicy.MIN_LENGTH} characters"
            if (!eval.strongEnough) return "New password is too weak" + (eval.warning?.let { ": $it" } ?: "")
            return when (val r = manager.changePassword(currentChars, newChars)) {
                UnlockResult.Success -> {
                    message.value = "Master password changed"
                    null
                }
                UnlockResult.WrongPassword -> "Current password is wrong"
                is UnlockResult.LockedOut -> "Too many wrong attempts. Wait ${formatCountdown(r.remainingMillis)}"
                UnlockResult.NoVault -> "No vault"
            }
        } finally {
            SecureBytes.wipe(newChars)
            SecureBytes.wipe(confirmChars)
            SecureBytes.wipe(currentChars)
        }
    }

    /** Verifies the master password, then irreversibly deletes the vault. */
    suspend fun destroyVault(password: String): String? {
        val chars = password.toCharArray()
        val r = try {
            manager.verifyMasterPassword(chars)
        } finally {
            SecureBytes.wipe(chars)
        }
        return when (r) {
            UnlockResult.Success -> {
                manager.destroy() // AppStateViewModel resets onboarding when the vault disappears
                null
            }
            UnlockResult.WrongPassword -> "Wrong master password"
            is UnlockResult.LockedOut -> "Too many wrong attempts. Wait ${formatCountdown(r.remainingMillis)}"
            UnlockResult.NoVault -> null
        }
    }
}
