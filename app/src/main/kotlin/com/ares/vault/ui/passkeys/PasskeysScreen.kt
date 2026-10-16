package com.ares.vault.ui.passkeys

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ares.vault.data.db.PasskeyEntity
import com.ares.vault.data.repo.PasskeyRepository
import com.ares.vault.ui.components.ConfirmDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class PasskeysViewModel @Inject constructor(private val repository: PasskeyRepository) : ViewModel() {
    val passkeys = repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun delete(p: PasskeyEntity) = viewModelScope.launch { repository.delete(p.id) }
}

/** Opens Android's "Passwords, passkeys & accounts" provider settings (Android 14+). */
fun openPasskeyProviderSettings(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    return runCatching { CredentialManager.create(context).createSettingsPendingIntent().send() }.isSuccess
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasskeysScreen(onBack: () -> Unit, vm: PasskeysViewModel = hiltViewModel()) {
    val passkeys by vm.passkeys.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<PasskeyEntity?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Passkeys") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sign in without passwords", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "When a site or app offers “Create a passkey”, choose AresVault. Your fingerprint then signs you in. " +
                                "Passkeys are kept in your encrypted vault and included in encrypted backups.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            Button(onClick = { openPasskeyProviderSettings(context) }) { Text("Turn on AresVault for passkeys") }
                        } else {
                            Text("Passkeys need Android 14 or newer.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (passkeys.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No passkeys yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(passkeys, key = { it.id }) { p ->
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Key, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(p.rpName.ifEmpty { p.rpId }) },
                    supportingContent = {
                        Text(
                            listOf(p.userName.ifEmpty { p.userDisplayName }, p.rpId).filter { it.isNotEmpty() }.joinToString(" · ") +
                                "\nCreated ${fmt(p.createdAt)}" + (p.lastUsedAt?.let { " · last used ${fmt(it)}" } ?: ""),
                        )
                    },
                    trailingContent = { IconButton(onClick = { deleting = p }) { Icon(Icons.Filled.Delete, "Delete passkey") } },
                )
            }
        }
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = "Delete passkey for ${p.rpId}?",
            text = "You won't be able to sign in to ${p.rpId} with this passkey any more. Remove it from the site's security settings too.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { vm.delete(p); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

private fun fmt(t: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
