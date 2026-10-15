package com.ares.vault.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ares.vault.apps.AppInfoProvider
import com.ares.vault.clipboard.SecureClipboard
import com.ares.vault.data.repo.CredentialRepository
import com.ares.vault.data.session.SettingsRepository
import com.ares.vault.ui.nav.DetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: CredentialRepository,
    private val settings: SettingsRepository,
    private val clipboard: SecureClipboard,
    val apps: AppInfoProvider,
) : ViewModel() {
    val id = savedState.toRoute<DetailRoute>().id

    val credential = repository.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history = repository.observeHistory(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)

    fun copySecret(label: String, value: String) {
        viewModelScope.launch {
            val seconds = settings.get()?.clipboardClearSeconds ?: SecureClipboard.DEFAULT_CLEAR_SECONDS
            val chars = value.toCharArray()
            clipboard.copySecret(label, chars, seconds)
            chars.fill('\u0000')
            message.value = "${label.replaceFirstChar { it.uppercase() }} copied — clears in $seconds s"
        }
    }

    fun copyPlain(label: String, value: String) {
        clipboard.copyPlain(label, value)
        message.value = "${label.replaceFirstChar { it.uppercase() }} copied"
    }

    fun toggleFavorite() {
        val c = credential.value ?: return
        viewModelScope.launch { repository.setFavorite(c.id, !c.favorite) }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.delete(id)
            onDone()
        }
    }
}
