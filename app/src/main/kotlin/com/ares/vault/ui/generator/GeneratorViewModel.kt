package com.ares.vault.ui.generator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ares.vault.clipboard.SecureClipboard
import com.ares.vault.core.crypto.SecureBytes
import com.ares.vault.core.generator.GeneratorConfig
import com.ares.vault.core.generator.GeneratorSettings
import com.ares.vault.core.generator.PassphraseConfig
import com.ares.vault.core.generator.PasswordGenerator
import com.ares.vault.core.generator.Preset
import com.ares.vault.core.generator.RandomConfig
import com.ares.vault.core.generator.StrengthLevel
import com.ares.vault.data.session.SettingsRepository
import com.ares.vault.data.settings.GeneratorSettingsRepository
import com.ares.vault.ui.PendingPassword
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class GeneratorViewModel @Inject constructor(
    private val repository: GeneratorSettingsRepository,
    private val clipboard: SecureClipboard,
    private val settings: SettingsRepository,
    private val pending: PendingPassword,
) : ViewModel() {

    data class UiState(
        val settings: GeneratorSettings = GeneratorSettings(),
        val selected: Preset = Preset.STRONG,
        /** Shown on screen; Compose text needs a String, so this is the one display copy. */
        val password: String = "",
        val entropyBits: Double = 0.0,
        val level: StrengthLevel = StrengthLevel.WEAK,
        val error: String? = null,
        val message: String? = null,
    ) {
        val config: GeneratorConfig get() = settings.configFor(selected)
    }

    private val generator = PasswordGenerator()
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = repository.load()
            _ui.update { it.copy(settings = settings, selected = settings.defaultPreset) }
            regenerate()
        }
    }

    fun select(preset: Preset) {
        _ui.update { it.copy(selected = preset) }
        regenerate()
    }

    fun updateCustom(transform: (RandomConfig) -> RandomConfig) {
        _ui.update { it.copy(settings = it.settings.copy(custom = transform(it.settings.custom))) }
        persistAndRegenerate()
    }

    fun updatePassphrase(transform: (PassphraseConfig) -> PassphraseConfig) {
        _ui.update { it.copy(settings = it.settings.copy(passphrase = transform(it.settings.passphrase))) }
        persistAndRegenerate()
    }

    fun makeSelectedDefault() {
        _ui.update { it.copy(settings = it.settings.copy(defaultPreset = it.selected)) }
        viewModelScope.launch {
            val saved = repository.save(_ui.value.settings)
            message(if (saved) "${_ui.value.selected.label} is now the autofill default" else "Unlock the vault to save the default")
        }
    }

    fun regenerate() {
        val config = _ui.value.config
        val error = when (config) {
            is RandomConfig -> config.validationError()
            is PassphraseConfig -> config.validationError()
        }
        if (error != null) {
            _ui.update { it.copy(error = error, password = "", entropyBits = 0.0, level = StrengthLevel.WEAK) }
            return
        }
        viewModelScope.launch {
            val chars = withContext(Dispatchers.Default) { generator.generate(config) }
            val bits = PasswordGenerator.entropyBits(config)
            _ui.update {
                it.copy(password = String(chars), entropyBits = bits, level = StrengthLevel.forBits(bits), error = null)
            }
            SecureBytes.wipe(chars)
        }
    }

    fun copy() {
        val pw = _ui.value.password
        if (pw.isEmpty()) return
        viewModelScope.launch {
            val seconds = settings.get()?.clipboardClearSeconds ?: SecureClipboard.DEFAULT_CLEAR_SECONDS
            val chars = pw.toCharArray()
            clipboard.copySecret("password", chars, seconds)
            SecureBytes.wipe(chars)
            message("Copied — clipboard clears in $seconds s")
        }
    }

    /** Hands the current password to the add-entry screen (in memory). */
    fun prepareNewEntry(): Boolean {
        val pw = _ui.value.password
        if (pw.isEmpty()) return false
        val chars = pw.toCharArray()
        pending.put(chars)
        SecureBytes.wipe(chars)
        return true
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    private fun message(text: String) = _ui.update { it.copy(message = text) }

    private fun persistAndRegenerate() {
        regenerate()
        viewModelScope.launch { repository.save(_ui.value.settings) }
    }

    override fun onCleared() {
        _ui.update { it.copy(password = "") }
    }
}
