package com.ares.vault.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ares.vault.apps.AppInfoProvider
import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.data.repo.CredentialRepository
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
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VaultListViewModel @Inject constructor(
    private val repository: CredentialRepository,
    private val manager: VaultManager,
    val apps: AppInfoProvider,
    private val prefs: AppPrefs,
) : ViewModel() {
    val query = MutableStateFlow("")

    data class ListState(val items: List<CredentialEntity> = emptyList(), val total: Int = 0, val loading: Boolean = true)

    val state: StateFlow<ListState> = combine(repository.observeAll(), query) { all, q ->
        ListState(VaultSearch.filter(all, q, apps::label), all.size, loading = false)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListState())

    /** Fingerprint was enabled before but its key got invalidated: offer to re-enable. */
    val offerBiometricReenroll = MutableStateFlow(prefs.biometricWanted && !manager.hasBiometricSlot())

    fun dismissReenroll() {
        offerBiometricReenroll.value = false
    }

    fun toggleFavorite(item: CredentialEntity) {
        viewModelScope.launch { repository.setFavorite(item.id, !item.favorite) }
    }

    fun lock() = manager.lock()
}
