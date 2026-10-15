package com.ares.vault.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ares.vault.BuildConfig
import com.ares.vault.core.generator.Preset
import com.ares.vault.data.session.AutoLockController
import com.ares.vault.ui.BiometricEnroller
import com.ares.vault.ui.components.AutofillSetupCard
import com.ares.vault.ui.components.PasswordField
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val generator by vm.generator.collectAsStateWithLifecycle()
    val biometric by vm.biometricEnabled.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<Dialog?>(null) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section("Security")
            val availability = vm.biometricAvailability(activity)
            SwitchItem(
                "Fingerprint unlock",
                when (availability) {
                    BiometricEnroller.Availability.AVAILABLE -> "Unlock with a hardware-protected key"
                    BiometricEnroller.Availability.NONE_ENROLLED -> "Add a fingerprint in system settings first"
                    BiometricEnroller.Availability.UNAVAILABLE -> "Not available on this device"
                },
                checked = biometric,
                enabled = availability == BiometricEnroller.Availability.AVAILABLE || biometric,
            ) { enable -> scope.launch { vm.setBiometric(activity, enable) } }
            ClickItem("Auto-lock", "After ${timeoutLabel(s.autoLockSeconds)} in the background") { dialog = Dialog.TIMEOUT }
            SwitchItem("Lock when screen turns off", "Recommended", s.lockOnScreenOff) { v ->
                vm.update { it.copy(lockOnScreenOff = v) }
            }
            ClickItem("Clear clipboard after", "${s.clipboardClearSeconds} seconds") { dialog = Dialog.CLIPBOARD }
            ClickItem("Change master password", "Re-wraps the vault key; your data isn't re-encrypted") { dialog = Dialog.CHANGE_PASSWORD }

            Section("Password generator")
            ClickItem("Default strength", "${generator.defaultPreset.label} — used by autofill “Generate”") { dialog = Dialog.PRESET }

            Section("Autofill")
            SwitchItem(
                "Show account names when locked",
                "Lists your usernames in autofill before you unlock. Filling still needs your fingerprint.",
                s.showAccountsWhenLocked,
            ) { v -> vm.update { it.copy(showAccountsWhenLocked = v) } }
            SwitchItem(
                "Ask before every fill",
                "Confirm with fingerprint (or screen lock) each time a password is filled",
                s.requireAuthToFill,
            ) { v -> vm.update { it.copy(requireAuthToFill = v) } }
            AutofillSetupCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            Section("Backup")
            ClickItem("Export / import", "Coming in Phase 6", enabled = false) {}

            Section("Danger zone")
            ClickItem("Delete vault", "Permanently erase all entries from this phone", destructive = true) { dialog = Dialog.DESTROY }

            Section("About")
            Text(
                "AresVault ${BuildConfig.VERSION_NAME}\n${vm.kdfInfo}\nNo network access · data never leaves this phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            Spacer(Modifier.height(24.dp))
        }

        when (dialog) {
            Dialog.TIMEOUT -> ChoiceDialog(
                "Auto-lock after",
                AutoLockController.TIMEOUT_OPTIONS,
                s.autoLockSeconds,
                ::timeoutLabel,
                onDismiss = { dialog = null },
            ) { v -> vm.update { it.copy(autoLockSeconds = v) } }
            Dialog.CLIPBOARD -> ChoiceDialog(
                "Clear clipboard after",
                listOf(15, 30, 60, 120),
                s.clipboardClearSeconds,
                { "$it seconds" },
                onDismiss = { dialog = null },
            ) { v -> vm.update { it.copy(clipboardClearSeconds = v) } }
            Dialog.PRESET -> ChoiceDialog(
                "Default strength",
                Preset.entries.toList(),
                generator.defaultPreset,
                { it.label },
                onDismiss = { dialog = null },
            ) { vm.setDefaultPreset(it) }
            Dialog.CHANGE_PASSWORD -> ChangePasswordDialog(vm, onDismiss = { dialog = null })
            Dialog.DESTROY -> DestroyDialog(vm, onDismiss = { dialog = null })
            null -> Unit
        }
    }
}

private enum class Dialog { TIMEOUT, CLIPBOARD, PRESET, CHANGE_PASSWORD, DESTROY }

private fun timeoutLabel(seconds: Int): String = when {
    seconds == 0 -> "immediately"
    seconds < 60 -> "$seconds seconds"
    seconds < 3600 -> "${seconds / 60} minute${if (seconds >= 120) "s" else ""}"
    else -> "1 hour"
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ClickItem(title: String, subtitle: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.outline
                destructive -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchItem(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = option == selected) { onSelect(option); onDismiss() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text("  " + label(option))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ChangePasswordDialog(vm: SettingsViewModel, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Change master password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(current, { current = it }, "Current password")
                PasswordField(new, { new = it }, "New password")
                PasswordField(confirm, { confirm = it }, "Confirm new password")
                if (busy) Text("Re-encrypting key…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    error = vm.changePassword(current, new, confirm)
                    busy = false
                    if (error == null) onDismiss()
                }
            }) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun DestroyDialog(vm: SettingsViewModel, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Delete vault?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("All entries will be permanently erased. This cannot be undone. Enter your master password to confirm.")
                PasswordField(password, { password = it }, "Master password")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && password.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    error = vm.destroyVault(password)
                    busy = false
                }
            }) { Text("Delete everything", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
