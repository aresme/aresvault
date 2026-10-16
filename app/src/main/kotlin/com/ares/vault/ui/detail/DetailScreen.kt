package com.ares.vault.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ares.vault.ui.components.ConfirmDialog
import com.ares.vault.ui.components.EntryAvatar
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(onBack: () -> Unit, onEdit: (String) -> Unit, vm: DetailViewModel = hiltViewModel()) {
    val c by vm.credential.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.message.value = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(c?.title.orEmpty()) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    val fav = c?.favorite == true
                    IconButton(onClick = vm::toggleFavorite) {
                        Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline, if (fav) "Unfavourite" else "Favourite")
                    }
                    IconButton(onClick = { onEdit(vm.id) }) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val item = c ?: return@Scaffold
        val appLabel = remember(item.appPackage) { item.appPackage?.let { vm.apps.label(it) } }
        val appIcon = remember(item.appPackage) { item.appPackage?.let { vm.apps.icon(it) } }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                EntryAvatar(item.title, appIcon, size = 56.dp)
                Column {
                    Text(item.title, style = MaterialTheme.typography.headlineSmall)
                    item.folder?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (item.username.isNotEmpty()) {
                        Field("Username", item.username) { vm.copyPlain("username", item.username) }
                        HorizontalDivider()
                    }
                    if (item.password.isNotEmpty()) {
                        SecretField("Password", item.password) { vm.copySecret("password", item.password) }
                    }
                }
            }
            val totp = remember(item.totpUri) { com.ares.vault.ui.totp.parseOrNull(item.totpUri) }
            if (totp != null) {
                Card(Modifier.fillMaxWidth()) {
                    com.ares.vault.ui.totp.TotpCode(totp, onCopy = { vm.copySecret("2FA code", it) }, modifier = Modifier.padding(16.dp))
                }
            }
            if (item.webDomain != null || item.extraUrls.isNotEmpty() || item.appPackage != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        item.webDomain?.let { Field("Website", it, null) }
                        item.extraUrls.forEach { Field("Also used on", it, null) }
                        item.appPackage?.let { pkg ->
                            Field(
                                "App",
                                (appLabel ?: "Not installed") + "\n$pkg" +
                                    (item.appCertSha256?.let { "\nCert " + it.take(16) + "…" } ?: "\nNo certificate pinned"),
                                null,
                            )
                        }
                    }
                }
            }
            if (item.notes.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) { Field("Notes", item.notes, null) }
            }
            if (history.isNotEmpty()) {
                Text("Password history", style = MaterialTheme.typography.titleSmall)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        history.forEachIndexed { i, h ->
                            if (i > 0) HorizontalDivider()
                            SecretField("Replaced ${formatDate(h.replacedAt)}", h.password) {
                                vm.copySecret("old password", h.password)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Created ${formatDate(item.createdAt)} · Updated ${formatDate(item.updatedAt)}" +
                    (item.lastUsedAt?.let { " · Last filled ${formatDate(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete “${c?.title}”?",
            text = "This entry and its password history will be permanently deleted.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { confirmDelete = false; vm.delete(onBack) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Field(label: String, value: String, onCopy: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        if (onCopy != null) IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "Copy $label") }
    }
}

@Composable
private fun SecretField(label: String, value: String, onCopy: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (revealed) value else "•".repeat(minOf(value.length, 16)),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
        IconButton(onClick = { revealed = !revealed }) {
            Icon(if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (revealed) "Hide" else "Reveal")
        }
        IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "Copy $label") }
    }
}

private fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
