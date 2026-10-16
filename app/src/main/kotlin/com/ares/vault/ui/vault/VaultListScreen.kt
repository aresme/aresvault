package com.ares.vault.ui.vault

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.ui.components.EntryAvatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultListScreen(
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onGenerator: () -> Unit,
    onSettings: () -> Unit,
    onPasskeys: () -> Unit,
    onReenrollBiometric: suspend (FragmentActivity) -> Unit,
    vm: VaultListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val controls by vm.controls.collectAsStateWithLifecycle()
    val reenroll by vm.offerBiometricReenroll.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var quick by remember { mutableStateOf<CredentialEntity?>(null) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    // New filter/sort/search: start from the top.
    LaunchedEffect(controls.filter, controls.folder, controls.sort, controls.query) { listState.scrollToItem(0) }

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
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Add entry") } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = controls.query,
                onValueChange = vm::setQuery,
                placeholder = { Text("Search title, username, site or app") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (controls.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Clear, "Clear") }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            FilterBar(state, controls, vm, onPasskeys)

            if (!state.loading && state.total == 0) {
                EmptyState("Your vault is empty", "Tap + to add a login, import from Google Password Manager in Settings, or let autofill save one for you.")
                return@Column
            }
            if (!state.loading && state.rows.isEmpty()) {
                EmptyState("Nothing here", if (controls.query.isNotEmpty()) "No logins match “${controls.query}”." else "No logins in this view.")
                return@Column
            }

            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 96.dp, end = 20.dp)) {
                    state.rows.forEach { row ->
                        when (row) {
                            is VaultRow.Header -> stickyHeader(key = row.key) { SectionHeader(row.title) }
                            is VaultRow.Favorites -> item(key = row.key) {
                                FavoritesStrip(row.items, vm, onOpen = { onOpen(it.id) }, onLongPress = { quick = it })
                            }
                            is VaultRow.Entry -> item(key = row.key) {
                                EntryRow(row.item, vm, indented = row.indented, onClick = { onOpen(row.item.id) }, onLongPress = { quick = row.item })
                            }
                            is VaultRow.SiteGroup -> item(key = row.key) {
                                GroupRow(row, vm) { vm.toggleGroup(row.siteKey) }
                            }
                        }
                    }
                }
                if (state.letters.size > 3) {
                    LetterScrubber(
                        letters = state.letters,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) { letter ->
                        val index = state.rows.indexOfFirst { it is VaultRow.Header && it.letter == letter }
                        if (index >= 0) scope.launch { listState.scrollToItem(index) }
                    }
                }
            }
        }
    }

    quick?.let { item ->
        QuickActions(
            item = item,
            vm = vm,
            onOpen = { quick = null; onOpen(item.id) },
            onDismiss = { quick = null },
        )
    }

    if (reenroll) {
        AlertDialog(
            onDismissRequest = vm::dismissReenroll,
            title = { Text("Re-enable fingerprint unlock?") },
            text = { Text("The fingerprints on this phone changed (or the vault key was rotated), so fingerprint unlock was turned off for safety.") },
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
private fun FilterBar(state: VaultListViewModel.ListState, controls: VaultListViewModel.Controls, vm: VaultListViewModel, onPasskeys: () -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Chip("All", controls.filter == VaultFilter.ALL && controls.folder == null, null) { vm.setFilter(VaultFilter.ALL) } }
            item { Chip("Favourites", controls.filter == VaultFilter.FAVORITES, Icons.Filled.Star) { vm.setFilter(VaultFilter.FAVORITES) } }
            item { Chip("Websites", controls.filter == VaultFilter.WEBSITES, Icons.Filled.Language) { vm.setFilter(VaultFilter.WEBSITES) } }
            item { Chip("Apps", controls.filter == VaultFilter.APPS, Icons.Filled.Apps) { vm.setFilter(VaultFilter.APPS) } }
            item { Chip("Passkeys", false, Icons.Filled.Key) { onPasskeys() } }
            items(state.folders) { f -> Chip(f, controls.folder == f, Icons.Filled.Folder) { vm.setFolder(f) } }
        }
        Box {
            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort: ${controls.sort.label}") }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                VaultSort.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label, fontWeight = if (s == controls.sort) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { vm.setSort(s); sortMenu = false },
                    )
                }
            }
        }
    }
    if (!state.loading && state.total > 0) {
        Text(
            if (state.shown == state.total) "${state.total} logins" else "${state.shown} of ${state.total} logins",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = icon?.let { { Icon(it, null, Modifier.size(18.dp)) } },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoritesStrip(items: List<CredentialEntity>, vm: VaultListViewModel, onOpen: (CredentialEntity) -> Unit, onLongPress: (CredentialEntity) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        SectionHeader("Favourites")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { item ->
                val icon = remember(item.appPackage) { item.appPackage?.let(vm.apps::icon) }
                Card(Modifier.width(96.dp).combinedClickable(onClick = { onOpen(item) }, onLongClick = { onLongPress(item) })) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EntryAvatar(item.title, icon, size = 44.dp)
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(item: CredentialEntity, vm: VaultListViewModel, indented: Boolean, onClick: () -> Unit, onLongPress: () -> Unit) {
    val icon = remember(item.appPackage) { item.appPackage?.let(vm.apps::icon) }
    // Inside a site group the account (username) is what tells rows apart.
    val title = if (indented) item.username.ifEmpty { item.title } else item.title
    val subtitle = remember(item, indented) {
        if (indented) {
            if (item.username.isEmpty()) "" else item.title
        } else {
            listOfNotNull(item.username.ifEmpty { null }, item.webDomain ?: item.appPackage?.let { vm.apps.label(it) ?: it }).joinToString(" · ")
        }
    }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(start = if (indented) 56.dp else 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (indented) {
            Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        } else {
            EntryAvatar(item.title, icon)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
private fun GroupRow(group: VaultRow.SiteGroup, vm: VaultListViewModel, onToggle: () -> Unit) {
    val first = group.items.first()
    val icon = remember(first.appPackage) { first.appPackage?.let(vm.apps::icon) }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onToggle).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EntryAvatar(group.title, icon)
        Column(Modifier.weight(1f)) {
            Text(group.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${group.items.size} accounts", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (group.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (group.expanded) "Collapse" else "Expand")
    }
}

/** A–Z strip on the right edge: tap or drag to jump to a section. */
@Composable
private fun LetterScrubber(letters: List<Char>, modifier: Modifier, onLetter: (Char) -> Unit) {
    var height by remember { mutableStateOf(1) }
    fun pick(y: Float) {
        val i = (y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)
        onLetter(letters[i])
    }
    Column(
        modifier.fillMaxHeight(0.85f).width(22.dp)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(letters) { detectTapGestures { pick(it.y) } }
            .pointerInput(letters) { detectVerticalDragGestures { change, _ -> pick(change.position.y) } },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { Text(it.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActions(item: CredentialEntity, vm: VaultListViewModel, onOpen: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (item.username.isNotEmpty()) {
                ListItem(
                    headlineContent = { Text("Copy username") },
                    supportingContent = { Text(item.username) },
                    leadingContent = { Icon(Icons.Filled.Person, null) },
                    modifier = Modifier.combinedClickable(onClick = { vm.copyUsername(item); onDismiss() }),
                )
            }
            ListItem(
                headlineContent = { Text("Copy password") },
                leadingContent = { Icon(Icons.Filled.ContentCopy, null) },
                modifier = Modifier.combinedClickable(onClick = { vm.copyPassword(item); onDismiss() }),
            )
            ListItem(
                headlineContent = { Text(if (item.favorite) "Remove from favourites" else "Add to favourites") },
                leadingContent = { Icon(if (item.favorite) Icons.Outlined.StarOutline else Icons.Filled.Star, null) },
                modifier = Modifier.combinedClickable(onClick = { vm.toggleFavorite(item); onDismiss() }),
            )
            ListItem(
                headlineContent = { Text("Open") },
                leadingContent = { Icon(Icons.Filled.Key, null) },
                modifier = Modifier.combinedClickable(onClick = onOpen),
            )
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun EmptyState(title: String, text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
