package com.ares.vault.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ares.vault.apps.AppInfoProvider
import com.ares.vault.core.domain.Domains
import com.ares.vault.core.generator.GeneratorSettings
import com.ares.vault.core.generator.PasswordGenerator
import com.ares.vault.core.generator.Preset
import com.ares.vault.core.generator.StrengthMeter
import com.ares.vault.data.repo.CredentialDraft
import com.ares.vault.data.repo.CredentialRepository
import com.ares.vault.data.repo.toDraft
import com.ares.vault.data.settings.GeneratorSettingsRepository
import com.ares.vault.ui.PendingPassword
import com.ares.vault.ui.nav.EditRoute
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
class EditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: CredentialRepository,
    private val generatorSettings: GeneratorSettingsRepository,
    private val pending: PendingPassword,
    val apps: AppInfoProvider,
) : ViewModel() {
    private val id: String? = savedState.toRoute<EditRoute>().id

    data class Form(
        val title: String = "",
        val username: String = "",
        val password: String = "",
        val website: String = "",
        val appPackage: String? = null,
        val appCertSha256: String? = null,
        val extraUrls: String = "",
        val folder: String = "",
        val notes: String = "",
        val favorite: Boolean = false,
    )

    data class UiState(
        val isNew: Boolean = true,
        val form: Form = Form(),
        val score: Int? = null,
        val websiteError: String? = null,
        val error: String? = null,
        val saving: Boolean = false,
        val generator: GeneratorSettings = GeneratorSettings(),
    )

    private val _ui = MutableStateFlow(UiState(isNew = id == null))
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val meter = StrengthMeter()
    private val generator = PasswordGenerator()
    private var scoreJob: Job? = null

    init {
        viewModelScope.launch {
            _ui.update { it.copy(generator = generatorSettings.load()) }
            if (id != null) {
                repository.get(id)?.toDraft()?.let { d ->
                    _ui.update {
                        it.copy(
                            form = Form(
                                title = d.title, username = d.username, password = d.password,
                                website = d.webDomain.orEmpty(), appPackage = d.appPackage,
                                appCertSha256 = d.appCertSha256, extraUrls = d.extraUrls.joinToString("\n"),
                                folder = d.folder.orEmpty(), notes = d.notes, favorite = d.favorite,
                            ),
                        )
                    }
                    rescore()
                }
            } else {
                pending.take()?.let { chars ->
                    edit { it.copy(password = String(chars)) }
                    chars.fill('\u0000')
                }
            }
        }
    }

    fun edit(transform: (Form) -> Form) {
        val before = _ui.value.form.password
        _ui.update { it.copy(form = transform(it.form), error = null, websiteError = null) }
        if (_ui.value.form.password != before) rescore()
    }

    fun generate(preset: Preset): String {
        val chars = generator.generate(_ui.value.generator.configFor(preset))
        return String(chars).also { chars.fill('\u0000') }
    }

    fun pickApp(packageName: String?) {
        if (packageName == null) {
            edit { it.copy(appPackage = null, appCertSha256 = null) }
            return
        }
        val label = apps.label(packageName)
        edit {
            it.copy(
                appPackage = packageName,
                appCertSha256 = apps.certSha256(packageName),
                title = it.title.ifBlank { label.orEmpty() },
            )
        }
    }

    fun save(onSaved: (String) -> Unit) {
        val f = _ui.value.form
        if (f.password.isEmpty()) {
            _ui.update { it.copy(error = "Password can't be empty") }
            return
        }
        val domain = if (f.website.isBlank()) null else Domains.normalizeHost(f.website)
        if (f.website.isNotBlank() && domain == null) {
            _ui.update { it.copy(websiteError = "Not a valid website address") }
            return
        }
        val extra = f.extraUrls.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { Domains.normalizeHost(it) ?: it }
        val title = f.title.trim().ifEmpty {
            f.appPackage?.let { apps.label(it) } ?: domain ?: f.username.ifBlank { "Untitled" }
        }
        val draft = CredentialDraft(
            title = title, username = f.username, password = f.password, notes = f.notes,
            appPackage = f.appPackage, appCertSha256 = f.appCertSha256, webDomain = domain,
            extraUrls = extra, folder = f.folder, favorite = f.favorite,
        )
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            val savedId = if (id == null) repository.create(draft) else id.also { repository.update(it, draft) }
            _ui.update { it.copy(saving = false) }
            onSaved(savedId)
        }
    }

    private fun rescore() {
        scoreJob?.cancel()
        scoreJob = viewModelScope.launch {
            delay(150)
            val pw = _ui.value.form.password
            if (pw.isEmpty()) {
                _ui.update { it.copy(score = null) }
                return@launch
            }
            val chars = pw.toCharArray()
            val score = withContext(Dispatchers.Default) { meter.measure(chars).score }
            chars.fill('\u0000')
            _ui.update { it.copy(score = score) }
        }
    }
}
