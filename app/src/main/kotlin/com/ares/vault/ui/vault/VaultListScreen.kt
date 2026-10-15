package com.ares.vault.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.ui.components.EntryAvatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onGenerator: () -> Unit,
    onSettings: () -> Unit,
    onReenrollBiometric: suspend (FragmentActivity) -> Unit,
    vm: VaultListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val reenroll by vm.offerBiometricReenroll.collectAsStateWithLifecycle()
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AresVault") },
                actions = {
                    IconButton(onClick = onGenerator) { Icon(Icons.Filled.Key, "Password generator") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                    IconButton(onClick = vm::lock) { Icon(Icons.Filled.Lock, "Lock now") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Add entry") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                placeholder = { Text("Search title, username, site or app") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Clear, "Clear") }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                state.loading -> Unit
                state.total == 0 -> EmptyState("Your vault is empty", "Tap + to add a login, or let autofill save one for you.")
                state.items.isEmpty() -> EmptyState("No matches", "Nothing matches “$query”.")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(state.items, key = { it.id }) { item ->
                        EntryRow(item, vm, onClick = { onOpen(item.id) })
                    }
                }
            }
        }
    }

    if (reenroll) {
        AlertDialog(
            onDismissRequest = vm::dismissReenroll,
            title = { Text("Re-enable fingerprint unlock?") },
            text = { Text("The fingerprints on this phone changed, so fingerprint unlock was turned off for safety.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissReenroll()
                    scope.launch { onReenrollBiometric(activity) }
                }) { Text("Re-enable") }
            },
            dismissButton = { TextButton(onClick = vm::dismissReenroll) { Text("Not now") } },
        )
    }
}

@Composable
private fun EntryRow(item: CredentialEntity, vm: VaultListViewModel, onClick: () -> Unit) {
    val icon = remember(item.appPackage) { item.appPackage?.let(vm.apps::icon) }
    val subtitle = remember(item) {
        listOfNotNull(
            item.username.ifEmpty { null },
            item.webDomain ?: item.appPackage?.let { vm.apps.label(it) ?: it },
        ).joinToString(" · ")
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EntryAvatar(item.title, icon)
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { vm.toggleFavorite(item) }) {
            Icon(
                if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                if (item.favorite) "Unfavourite" else "Favourite",
                tint = if (item.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
