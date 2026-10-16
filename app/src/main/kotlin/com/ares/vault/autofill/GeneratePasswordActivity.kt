package com.ares.vault.autofill

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ares.vault.core.generator.GeneratorConfig
import com.ares.vault.core.generator.GeneratorSettings
import com.ares.vault.core.generator.PasswordGenerator
import com.ares.vault.core.generator.Preset
import com.ares.vault.core.generator.RandomConfig
import com.ares.vault.core.generator.StrengthLevel
import com.ares.vault.data.settings.GeneratorSettingsRepository
import com.ares.vault.ui.theme.AresVaultTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class GeneratePasswordViewModel @Inject constructor(settingsRepo: GeneratorSettingsRepository) : ViewModel() {
    data class UiState(val preset: Preset, val noSymbols: Boolean = false, val password: String = "", val bits: Double = 0.0)

    private val settings: GeneratorSettings = settingsRepo.loadForAutofill()
    private val generator = PasswordGenerator()
    private val _ui = MutableStateFlow(UiState(settings.defaultPreset))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        regenerate()
    }

    private fun config(s: UiState): GeneratorConfig {
        val base = settings.configFor(s.preset)
        return if (s.noSymbols && base is RandomConfig) base.withoutSymbols() else base
    }

    fun select(preset: Preset) {
        _ui.update { it.copy(preset = preset) }
        regenerate()
    }

    /** Some sites reject symbols (spec §6.1). */
    fun setNoSymbols(value: Boolean) {
        _ui.update { it.copy(noSymbols = value) }
        regenerate()
    }

    fun regenerate() {
        val config = config(_ui.value)
        val chars = generator.generate(config)
        _ui.update { it.copy(password = String(chars), bits = PasswordGenerator.entropyBits(config)) }
        chars.fill('\u0000')
    }

    fun isRandom() = settings.configFor(_ui.value.preset) is RandomConfig

    override fun onCleared() = _ui.update { it.copy(password = "") }
}

/**
 * "Generate strong password" bottom sheet for sign-up forms. Generating needs no unlock (no
 * stored secret is read); the password is saved later through the normal save prompt.
 */
@AndroidEntryPoint
class GeneratePasswordActivity : FragmentActivity() {
    @Inject lateinit var contexts: FillContextFactory
    @Inject lateinit var factory: DatasetFactory
    private val vm: GeneratePasswordViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureAutofillWindow()
        val structure = IntentCompat.getParcelableExtra(intent, AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        val ctx = structure?.let { contexts.create(it, null) }
        val targets = ctx?.let { it.analysis.newPasswords + it.analysis.confirmPasswords }.orEmpty()
        if (ctx == null || targets.isEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        setContent {
            AresVaultTheme {
                val ui by vm.ui.collectAsStateWithLifecycle()
                AutofillSheet(onDismiss = ::cancel) {
                    Text("Strong password for ${ctx.targetLabel}", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ui.password,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 20.sp,
                            modifier = Modifier.weight(1f).testTag("generated"),
                        )
                        IconButton(onClick = vm::regenerate) { Icon(Icons.Filled.Refresh, "Regenerate") }
                    }
                    Text(
                        "${StrengthLevel.forBits(ui.bits).label} · ${ui.bits.roundToInt()} bits · fills ${if (targets.size > 1) "both password fields" else "the password field"}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Preset.entries.forEach { p ->
                            FilterChip(selected = ui.preset == p, onClick = { vm.select(p) }, label = { Text(p.label) })
                        }
                    }
                    if (vm.isRandom()) {
                        Row(
                            Modifier.fillMaxWidth().toggleable(ui.noSymbols, role = Role.Switch, onValueChange = vm::setNoSymbols),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("No symbols (for sites that reject them)", Modifier.weight(1f))
                            Switch(checked = ui.noSymbols, onCheckedChange = null)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = ::cancel) { Text("Cancel") }
                        Button(onClick = { use(ui.password, targets) }) { Text("Use password") }
                    }
                }
            }
        }
    }

    private fun use(password: String, targets: List<android.view.autofill.AutofillId>) {
        val dataset = factory.authResult(targets.associateWith { password })
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
        finish()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
