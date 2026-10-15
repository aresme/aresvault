package com.ares.vault.ui.generator

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ares.vault.core.generator.PassphraseConfig
import com.ares.vault.core.generator.Preset
import com.ares.vault.core.generator.RandomConfig
import com.ares.vault.core.generator.StrengthLevel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GeneratorScreen(onBack: () -> Unit, onSaveAsEntry: () -> Unit, vm: GeneratorViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Password generator") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PasswordCard(ui, onRegenerate = vm::regenerate, onCopy = vm::copy)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset.entries.forEach { preset ->
                    FilterChip(
                        selected = ui.selected == preset,
                        onClick = { vm.select(preset) },
                        label = { Text(preset.label) },
                        leadingIcon = if (ui.settings.defaultPreset == preset) {
                            { Icon(Icons.Filled.Star, "Autofill default", Modifier.padding(0.dp)) }
                        } else null,
                    )
                }
            }

            val isDefault = ui.settings.defaultPreset == ui.selected
            TextButton(onClick = vm::makeSelectedDefault, enabled = !isDefault) {
                Icon(if (isDefault) Icons.Filled.Star else Icons.Outlined.StarOutline, null)
                Spacer(Modifier.width(8.dp))
                Text(if (isDefault) "Used by autofill" else "Use ${ui.selected.label} for autofill")
            }

            OutlinedButton(
                onClick = { if (vm.prepareNewEntry()) onSaveAsEntry() },
                enabled = ui.password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save as new entry") }

            when (ui.selected) {
                Preset.CUSTOM -> CustomOptions(ui.settings.custom, vm::updateCustom)
                Preset.PASSPHRASE -> PassphraseOptions(ui.settings.passphrase, vm::updatePassphrase)
                else -> PresetDescription(Preset.fixedConfig(ui.selected)!!)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PasswordCard(ui: GeneratorViewModel.UiState, onRegenerate: () -> Unit, onCopy: () -> Unit) {
    val fraction by animateFloatAsState(StrengthLevel.fraction(ui.entropyBits), label = "strength")
    val color by animateColorAsState(strengthColor(ui.level), label = "strengthColor")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.error != null) {
                Text(ui.error, color = MaterialTheme.colorScheme.error)
            } else {
                Text(
                    colorize(ui.password),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                )
            }
            LinearProgressIndicator(
                progress = { fraction },
                color = color,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${ui.level.label} · ${ui.entropyBits.roundToInt()} bits",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRegenerate) { Icon(Icons.Filled.Refresh, "Regenerate") }
                FilledTonalButton(onClick = onCopy, enabled = ui.password.isNotEmpty()) {
                    Icon(Icons.Filled.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy")
                }
            }
        }
    }
}

@Composable
private fun PresetDescription(config: RandomConfig) {
    val classes = listOfNotNull(
        "uppercase".takeIf { config.upper },
        "lowercase".takeIf { config.lower },
        "digits".takeIf { config.digits },
        "symbols".takeIf { config.symbols },
    )
    Text(
        "${config.length} characters: ${classes.joinToString(", ")}. At least one of each.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CustomOptions(config: RandomConfig, update: ((RandomConfig) -> RandomConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Length: ${config.length}", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = config.length.toFloat(),
            onValueChange = { v -> update { it.copy(length = v.roundToInt()) } },
            valueRange = RandomConfig.MIN_LENGTH.toFloat()..RandomConfig.MAX_LENGTH.toFloat(),
        )
        ToggleRow("Uppercase (A–Z)", config.upper) { v -> update { it.copy(upper = v) } }
        ToggleRow("Lowercase (a–z)", config.lower) { v -> update { it.copy(lower = v) } }
        ToggleRow("Digits (0–9)", config.digits) { v -> update { it.copy(digits = v) } }
        ToggleRow("Symbols (!@#…)", config.symbols) { v -> update { it.copy(symbols = v) } }
        ToggleRow("Avoid ambiguous (0 O l 1 I)", config.avoidAmbiguous) { v -> update { it.copy(avoidAmbiguous = v) } }
        if (config.digits) {
            Stepper("Minimum digits", config.minDigits, 1..10) { v -> update { it.copy(minDigits = v) } }
        }
        if (config.symbols) {
            Stepper("Minimum symbols", config.minSymbols, 1..10) { v -> update { it.copy(minSymbols = v) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PassphraseOptions(config: PassphraseConfig, update: ((PassphraseConfig) -> PassphraseConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Words: ${config.words}", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = config.words.toFloat(),
            onValueChange = { v -> update { it.copy(words = v.roundToInt()) } },
            valueRange = PassphraseConfig.MIN_WORDS.toFloat()..PassphraseConfig.MAX_WORDS.toFloat(),
            steps = PassphraseConfig.MAX_WORDS - PassphraseConfig.MIN_WORDS - 1,
        )
        Text("Separator", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PassphraseConfig.SEPARATORS.forEach { sep ->
                FilterChip(
                    selected = config.separator == sep,
                    onClick = { update { it.copy(separator = sep) } },
                    label = {
                        Text(
                            when (sep) {
                                " " -> "space"
                                "" -> "none"
                                else -> sep
                            },
                        )
                    },
                )
            }
        }
        ToggleRow("Capitalise words", config.capitalize) { v -> update { it.copy(capitalize = v) } }
        ToggleRow("Add a number", config.includeNumber) { v -> update { it.copy(includeNumber = v) } }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { onChange(value - 1) }, enabled = value > range.first) { Text("−") }
        Text("$value", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onChange(value + 1) }, enabled = value < range.last) { Text("+") }
    }
}

private val DigitColor = Color(0xFF6CB6FF)
private val SymbolColor = Color(0xFFE5484D)

/** Digits blue, symbols red, letters default — makes passwords easier to read/type. */
private fun colorize(password: String): AnnotatedString = buildAnnotatedString {
    password.forEach { c ->
        when {
            c.isDigit() -> withStyle(SpanStyle(color = DigitColor)) { append(c) }
            !c.isLetter() -> withStyle(SpanStyle(color = SymbolColor)) { append(c) }
            else -> append(c)
        }
    }
}

private fun strengthColor(level: StrengthLevel): Color = when (level) {
    StrengthLevel.WEAK -> Color(0xFFE5484D)
    StrengthLevel.FAIR -> Color(0xFFF5A524)
    StrengthLevel.GOOD -> Color(0xFFC9D34E)
    StrengthLevel.STRONG -> Color(0xFF8BD34E)
    StrengthLevel.VERY_STRONG, StrengthLevel.EXCELLENT -> Color(0xFF30C48D)
}
