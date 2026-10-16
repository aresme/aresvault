package com.ares.vault.backup

import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** "Open the email-backup step" request from the reminder, consumed once the vault is unlocked. */
@Singleton
class PendingAction @Inject constructor() {
    val emailBackup = MutableStateFlow(false)
}
