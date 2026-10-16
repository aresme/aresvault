package com.ares.vault.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ares.vault.apps.AppInfoProvider
import com.ares.vault.clipboard.SecureClipboard
import com.ares.vault.core.autofill.CredentialMatcher
import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.data.repo.CredentialRepository
import com.ares.vault.data.session.SettingsRepository
import com.ares.vault.data.session.VaultManager
import com.ares.vault.prefs.AppPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class VaultListViewModel @Inject constructor(
    private val repository: CredentialRepository,
    private val manager: VaultManager,
    private val settings: SettingsRepository,
    private val clipboard: SecureClipboard,
    val apps: AppInfoProvider,
    prefs: AppPrefs,
) : ViewModel() {

    data class Controls(
        val query: String = "",
        val filter: VaultFilter = VaultFilter.ALL,
        val folder: String? = null,
        val sort: VaultSort = VaultSort.NAME,
        val expanded: Set<String> = emptySet(),
    )

    data class ListState(
        val rows: List<VaultRow> = emptyList(),
        val letters: List<Char> = emptyList(),
        val folders: List<String> = emptyList(),
        val total: Int = 0,
        val shown: Int = 0,
        val loading: Boolean = true,
    )

    val controls = MutableStateFlow(Controls())
    val message = MutableStateFlow<String?>(null)

    private val matcher = CredentialMatcher()
    private val siteKeys = ConcurrentHashMap<String, String>()

    /** Same site = same registrable domain (PSL) or same app package; cached per host. */
    private fun siteKey(c: CredentialEntity): String? =
        c.appPackage ?: c.webDomain?.let { host -> siteKeys.getOrPut(host) { matcher.siteKey(host) ?: host } }

    val state: StateFlow<ListState> = combine(repository.observeAll(), controls) { all, c ->
        val rows = VaultGrouping.build(all, c.query, c.filter, c.folder, c.sort, c.expanded, apps::label, ::siteKey)
        ListState(
            rows = rows,
            letters = VaultGrouping.letters(rows),
            folders = VaultGrouping.folders(all),
            total = all.size,
            shown = rows.sumOf {
                when (it) {
                    is VaultRow.Entry -> if (it.indented) 0 else 1
                    is VaultRow.SiteGroup -> it.items.size
                    else -> 0
                }
            },
            loading = false,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListState())

    /** Fingerprint was enabled before but its key got invalidated: offer to re-enable. */
    val offerBiometricReenroll = MutableStateFlow(prefs.biometricWanted && !manager.hasBiometricSlot())

    fun setQuery(q: String) = controls.update { it.copy(query = q) }
    fun setFilter(f: VaultFilter) = controls.update { it.copy(filter = f, folder = null) }
    fun setFolder(folder: String?) = controls.update { it.copy(folder = if (it.folder == folder) null else folder, filter = VaultFilter.ALL) }
    fun setSort(s: VaultSort) = controls.update { it.copy(sort = s) }
    fun toggleGroup(key: String) = controls.update { c ->
        c.copy(expanded = if (key in c.expanded) c.expanded - key else c.expanded + key)
    }

    fun dismissReenroll() {
        offerBiometricReenroll.value = false
    }

    fun toggleFavorite(item: CredentialEntity) {
        viewModelScope.launch { repository.setFavorite(item.id, !item.favorite) }
    }

    fun copyUsername(item: CredentialEntity) {
        clipboard.copyPlain("username", item.username)
        message.value = "Username copied"
    }

    fun copyPassword(item: CredentialEntity) {
        viewModelScope.launch {
            val seconds = settings.get()?.clipboardClearSeconds ?: SecureClipboard.DEFAULT_CLEAR_SECONDS
            val chars = item.password.toCharArray()
            clipboard.copySecret("password", chars, seconds)
            chars.fill('\u0000')
            message.value = "Password copied — clears in $seconds s"
        }
    }

    fun lock() = manager.lock()
}
