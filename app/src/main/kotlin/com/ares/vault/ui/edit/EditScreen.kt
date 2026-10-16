package com.ares.vault.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ares.vault.apps.AppInfoProvider
import com.ares.vault.core.generator.PasswordGenerator
import com.ares.vault.core.generator.Preset
import com.ares.vault.core.generator.StrengthLevel
import com.ares.vault.ui.components.EntryAvatar
import com.ares.vault.ui.components.PasswordField
import com.ares.vault.ui.components.ScoreBar
import com.ares.vault.ui.components.scoreLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(onScanTotp: () -> Unit, onBack: () -> Unit, onSaved: (String) -> Unit, vm: EditViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val f = ui.form
    var showGenerator by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    // Returning from the QR scanner: pick up the scanned code.
    LaunchedEffect(Unit) { vm.consumePendingScan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ui.isNew) "New login" else "Edit login") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { vm.save(onSaved) }, enabled = !ui.saving) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Field("Title", f.title) { v -> vm.edit { it.copy(title = v) } }
            Field("Username or email", f.username, KeyboardType.Email) { v -> vm.edit { it.copy(username = v) } }
            PasswordField(
                value = f.password,
                onValueChange = { v -> vm.edit { it.copy(password = v) } },
                label = "Password",
                isError = ui.error != null,
                supportingText = ui.error,
                trailingExtra = {
                    IconButton(onClick = { showGenerator = true }) { Icon(Icons.Filled.Casino, "Generate password") }
                },
            )
            ui.score?.let { ScoreBar(it, scoreLabel(it)) }
            TotpSection(f.totpUri, ui.totpError, onScan = onScanTotp, onKey = { showKeyDialog = true }, onRemove = vm::removeTotp)
            Field(
                "Website (e.g. example.com)", f.website, KeyboardType.Uri,
                error = ui.websiteError,
            ) { v -> vm.edit { it.copy(website = v) } }
            LinkedApp(f.appPackage, f.appCertSha256, vm.apps, onPick = { showAppPicker = true }, onClear = { vm.pickApp(null) })
            OutlinedTextField(
                value = f.extraUrls,
                onValueChange = { v -> vm.edit { it.copy(extraUrls = v) } },
                label = { Text("Other websites (one per line)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
            )
            Field("Folder / tag", f.folder) { v -> vm.edit { it.copy(folder = v) } }
            OutlinedTextField(
                value = f.notes,
                onValueChange = { v -> vm.edit { it.copy(notes = v) } },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Favourite", Modifier.weight(1f))
                Switch(checked = f.favorite, onCheckedChange = { v -> vm.edit { it.copy(favorite = v) } })
            }
            Button(onClick = { vm.save(onSaved) }, enabled = !ui.saving, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showGenerator) {
        GeneratorSheet(
            defaultPreset = ui.generator.defaultPreset,
            entropy = { PasswordGenerator.entropyBits(ui.generator.configFor(it)) },
            generate = vm::generate,
            onUse = { pw -> vm.edit { it.copy(password = pw) }; showGenerator = false },
            onDismiss = { showGenerator = false },
        )
    }
    if (showKeyDialog) {
        SetupKeyDialog(onDismiss = { showKeyDialog = false }) { key, digits, period ->
            vm.setTotpKey(key, digits, period).also { if (it == null) showKeyDialog = false }
        }
    }
    if (showAppPicker) {
        AppPickerDialog(vm.apps, onPick = { vm.pickApp(it); showAppPicker = false }, onDismiss = { showAppPicker = false })
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LinkedApp(pkg: String?, cert: String?, apps: AppInfoProvider, onPick: () -> Unit, onClear: () -> Unit) {
    if (pkg == null) {
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Apps, null)
            Text("  Link an Android app")
        }
        return
    }
    val label = remember(pkg) { apps.label(pkg) }
    val icon = remember(pkg) { apps.icon(pkg) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EntryAvatar(label ?: pkg, icon)
        Column(Modifier.weight(1f)) {
            Text(label ?: pkg, style = MaterialTheme.typography.titleSmall)
            Text(
                if (cert != null) "Signature pinned" else "App not installed — signature not pinned",
                style = MaterialTheme.typography.bodySmall,
                color = if (cert != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onPick) { Text("Change") }
        TextButton(onClick = onClear) { Text("Remove") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GeneratorSheet(
    defaultPreset: Preset,
    entropy: (Preset) -> Double,
    generate: (Preset) -> String,
    onUse: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var preset by remember { mutableStateOf(defaultPreset) }
    var password by remember { mutableStateOf(generate(defaultPreset)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Generate password", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset.entries.forEach { p ->
                    FilterChip(
                        selected = p == preset,
                        onClick = { preset = p; password = generate(p) },
                        label = { Text(p.label) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(password, fontFamily = FontFamily.Monospace, fontSize = 18.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { password = generate(preset) }) { Icon(Icons.Filled.Refresh, "Regenerate") }
            }
            val bits = entropy(preset)
            Text(
                "${StrengthLevel.forBits(bits).label} · ${bits.roundToInt()} bits",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { onUse(password) }, modifier = Modifier.fillMaxWidth()) { Text("Use this password") }
        }
    }
}

@Composable
private fun AppPickerDialog(apps: AppInfoProvider, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val all by produceState<List<AppInfoProvider.InstalledApp>?>(null) {
        value = withContext(Dispatchers.Default) { apps.launchableApps() }
    }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link an app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.ares.vault.ui.components.ExcludeWindowFromAutofill()
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search apps") }, singleLine = true)
                val list = all
                if (list == null) {
                    Text("Loading…")
                } else {
                    val filtered = list.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            val icon = remember(app.packageName) { apps.icon(app.packageName) }
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(app.packageName) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                EntryAvatar(app.label, icon, size = 32.dp)
                                Column {
                                    Text(app.label)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TotpSection(uri: String?, error: String?, onScan: () -> Unit, onKey: () -> Unit, onRemove: () -> Unit) {
    val spec = remember(uri) { com.ares.vault.ui.totp.parseOrNull(uri) }
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (spec == null) {
                Text("Two-factor (2FA) code", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Optional. Turn on 2FA on the website, then scan its QR code. Keeping 2FA in the same vault as the " +
                        "password is convenient but less protective than a separate authenticator.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onScan) { Text("Scan QR code") }
                    TextButton(onClick = onKey) { Text("Enter setup key") }
                }
            } else {
                com.ares.vault.ui.totp.TotpCode(spec, onCopy = {})
                TextButton(onClick = onRemove) { Text("Remove 2FA", color = MaterialTheme.colorScheme.error) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupKeyDialog(onDismiss: () -> Unit, onSubmit: (String, Int, Int) -> String?) {
    var key by remember { mutableStateOf("") }
    var digits by remember { mutableStateOf(6) }
    var period by remember { mutableStateOf(30) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter setup key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.ares.vault.ui.components.ExcludeWindowFromAutofill()
                OutlinedTextField(key, { key = it; error = null }, label = { Text("Setup key (letters A–Z, 2–7)") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 8).forEach { d -> FilterChip(selected = digits == d, onClick = { digits = d }, label = { Text("$d digits") }) }
                    listOf(30, 60).forEach { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text("${p}s") }) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { error = onSubmit(key, digits, period) }, enabled = key.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
