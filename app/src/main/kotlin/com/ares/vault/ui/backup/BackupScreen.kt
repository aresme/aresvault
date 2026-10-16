package com.ares.vault.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ares.vault.core.backup.BackupCodec
import com.ares.vault.ui.components.PasswordField
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit, onImportAuthenticator: () -> Unit, vm: BackupViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), vm::exportEncrypted)
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::importEncrypted)
    val openCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::importCsv)
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), vm::exportCsv)
    val today = LocalDate.now().toString()
    val email by vm.email.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { vm.emailIntent.collect { context.startActivity(it) } }
    LaunchedEffect(Unit) { if (vm.emailNow && email.address.isNotBlank()) dialog = Dialog.EMAIL }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & import") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Section(
                "Email encrypted backup",
                "Sends the encrypted .aresvault file to your dedicated backup address through your email app. " +
                    "AresVault itself has no internet access, and the attachment can't be opened without the backup password.",
            ) {
                EmailSettings(
                    email,
                    onSave = vm::saveEmail,
                    onReminder = { on ->
                        if (on && android.os.Build.VERSION.SDK_INT >= 33) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        vm.setReminder(on)
                    },
                )
                Button(onClick = { dialog = Dialog.EMAIL }, enabled = !ui.busy && email.address.isNotBlank()) { Text("Email encrypted backup") }
            }
            Section(
                "Encrypted backup",
                "Saves every login (with password history) to a .${BackupCodec.FILE_EXTENSION} file encrypted " +
                    "with a backup password you choose. Store it off this phone, e.g. on a computer or USB drive.",
            ) {
                Button(onClick = { dialog = Dialog.EXPORT }, enabled = !ui.busy) { Text("Export encrypted backup") }
                OutlinedButton(onClick = { dialog = Dialog.IMPORT }, enabled = !ui.busy) { Text("Restore from backup") }
            }
            Section(
                "Import from Google Password Manager",
                "In Google Password Manager: Settings → Export passwords. Then pick the CSV here. " +
                    "Firefox CSV exports work too. Android-app logins keep their app link.",
            ) {
                OutlinedButton(onClick = {
                    vm.beforePicker()
                    openCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "*/*"))
                }, enabled = !ui.busy) { Text("Import CSV") }
            }
            Section(
                "Import 2FA codes from Google Authenticator",
                "In Google Authenticator: ⋮ → Transfer accounts → Export accounts. Scan the QR code(s) with AresVault. " +
                    "Codes are attached to matching logins, or saved as new 2FA entries.",
            ) {
                OutlinedButton(onClick = onImportAuthenticator, enabled = !ui.busy) { Text("Scan export QR codes") }
            }
            Section(
                "Unencrypted export",
                "A plain CSV for moving to another password manager. Anyone with the file can read every password.",
            ) {
                TextButton(onClick = { dialog = Dialog.PLAINTEXT }, enabled = !ui.busy) {
                    Text("Export unencrypted CSV", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    when (dialog) {
        Dialog.EXPORT -> BackupPasswordDialog(
            title = "Backup password",
            confirm = true,
            onDismiss = { dialog = null },
        ) { pw, confirm ->
            vm.prepareBackupPassword(pw, confirm) ?: run {
                dialog = null
                vm.beforePicker()
                createBackup.launch("AresVault-$today.${BackupCodec.FILE_EXTENSION}")
                null
            }
        }
        Dialog.EMAIL -> BackupPasswordDialog(
            title = "Backup password",
            confirm = true,
            onDismiss = { dialog = null },
            actionLabel = "Open email app",
        ) { pw, confirm ->
            vm.prepareBackupPassword(pw, confirm) ?: run {
                dialog = null
                vm.emailBackup()
                null
            }
        }
        Dialog.IMPORT -> BackupPasswordDialog(
            title = "Backup password",
            confirm = false,
            onDismiss = { dialog = null },
        ) { pw, _ ->
            vm.setImportPassword(pw)
            dialog = null
            vm.beforePicker()
            openBackup.launch(arrayOf("*/*"))
            null
        }
        Dialog.PLAINTEXT -> PlaintextDialog(vm, onDismiss = { dialog = null }) {
            dialog = null
            vm.beforePicker()
            createCsv.launch("AresVault-passwords-$today.csv")
        }
        null -> Unit
    }

    if (ui.importedCsv != null) {
        AlertDialog(
            onDismissRequest = vm::dismissCsvPrompt,
            title = { Text("Delete the CSV file now") },
            text = {
                Text(
                    "The file you imported contains all your passwords in plain text. Delete it, and empty any " +
                        "\"Recently deleted\" folder in your Files app.",
                )
            },
            confirmButton = { TextButton(onClick = vm::deleteImportedCsv) { Text("Delete file", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = vm::dismissCsvPrompt) { Text("I'll do it myself") } },
        )
    }
}

private enum class Dialog { EXPORT, IMPORT, PLAINTEXT, EMAIL }

@Composable
private fun Section(title: String, text: String, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions()
        }
    }
}

/** [onSubmit] returns an error to show, or null when done. */
@Composable
private fun BackupPasswordDialog(
    title: String,
    confirm: Boolean,
    onDismiss: () -> Unit,
    actionLabel: String = "Choose file",
    onSubmit: (String, String) -> String?,
) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirm) {
                    Text(
                        "Use a strong password you won't forget (it can be your master password). Lost backup password = lost backup.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PasswordField(pw, { pw = it; error = null }, "Backup password")
                if (confirm) PasswordField(pw2, { pw2 = it; error = null }, "Confirm backup password")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onSubmit(pw, pw2) }, enabled = pw.isNotEmpty()) { Text(actionLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PlaintextDialog(vm: BackupViewModel, onDismiss: () -> Unit, onVerified: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export without encryption?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The CSV will contain every username and password in plain text. Any app or person with access " +
                        "to the file can read them. Prefer the encrypted backup unless you are moving to another app.",
                    color = MaterialTheme.colorScheme.error,
                )
                PasswordField(pw, { pw = it; error = null }, "Master password")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = pw.isNotEmpty() && !busy, onClick = {
                busy = true
                scope.launch {
                    error = vm.verifyForPlaintextExport(pw)
                    busy = false
                    if (error == null) onVerified()
                }
            }) { Text("I understand, export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmailSettings(state: BackupViewModel.EmailState, onSave: (String) -> String?, onReminder: (Boolean) -> Unit) {
    var address by remember(state.address) { mutableStateOf(state.address) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.OutlinedTextField(
        value = address,
        onValueChange = { address = it; error = null },
        label = { Text("Backup email address") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
        trailingIcon = {
            if (address.trim() != state.address) TextButton(onClick = { error = onSave(address) }) { Text("Save") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Weekly reminder")
            Text(
                if (state.lastSentAt == 0L) "Never sent" else "Last sent ${java.text.DateFormat.getDateInstance().format(java.util.Date(state.lastSentAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Switch(checked = state.reminder, onCheckedChange = onReminder, enabled = state.address.isNotBlank())
    }
}
