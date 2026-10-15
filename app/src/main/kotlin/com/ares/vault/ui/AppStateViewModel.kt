package com.ares.vault.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ares.vault.data.session.VaultManager
import com.ares.vault.data.session.VaultSession
import com.ares.vault.data.session.VaultState
import com.ares.vault.prefs.AppPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AppScreen { LOADING, ONBOARDING, LOCKED, UNLOCKED }

/** Decides the top-level screen. Locking swaps out the whole vault UI (and its ViewModels). */
@HiltViewModel
class AppStateViewModel @Inject constructor(
    manager: VaultManager,
    session: VaultSession,
    private val prefs: AppPrefs,
) : ViewModel() {

    init {
        // At start-up: a vault without finished onboarding means setup was interrupted
        // (process killed on the fingerprint step) — don't re-run it.
        val exists = manager.exists.value
        if (exists && !prefs.onboardingDone.value) prefs.setOnboardingDone(true)
        if (!exists && prefs.onboardingDone.value) prefs.clear()
        // Afterwards: the vault being deleted restarts onboarding from scratch.
        viewModelScope.launch {
            manager.exists.drop(1).collect { if (!it) prefs.clear() }
        }
    }

    val screen: StateFlow<AppScreen> = combine(manager.exists, session.state, prefs.onboardingDone) { exists, state, done ->
        when {
            !exists || !done -> AppScreen.ONBOARDING
            state is VaultState.Unlocked -> AppScreen.UNLOCKED
            else -> AppScreen.LOCKED
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppScreen.LOADING)
}
