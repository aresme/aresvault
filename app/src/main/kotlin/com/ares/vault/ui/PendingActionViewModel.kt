package com.ares.vault.ui

import androidx.lifecycle.ViewModel
import com.ares.vault.backup.PendingAction
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PendingActionViewModel @Inject constructor(private val pending: PendingAction) : ViewModel() {
    val emailBackup = pending.emailBackup
    fun consume() {
        pending.emailBackup.value = false
    }
}
