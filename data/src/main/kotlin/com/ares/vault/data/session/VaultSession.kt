package com.ares.vault.data.session

import com.ares.vault.core.crypto.SecureBytes
import com.ares.vault.data.AresLog
import com.ares.vault.data.db.VaultDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface VaultState {
    data object Locked : VaultState
    class Unlocked(val database: VaultDatabase) : VaultState
}

/**
 * Owns the DEK and the open database while the vault is unlocked (spec §4.3).
 * Locking closes the database and zeroes every key copy we hold.
 */
@Singleton
class VaultSession @Inject constructor() {
    private val _state = MutableStateFlow<VaultState>(VaultState.Locked)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    private var dek: ByteArray? = null
    private var dbKey: ByteArray? = null

    val isUnlocked: Boolean get() = _state.value is VaultState.Unlocked

    /** Takes ownership of [dek] and [dbKey]; they are wiped on [lock]. */
    @Synchronized
    fun open(database: VaultDatabase, dek: ByteArray, dbKey: ByteArray) {
        closeLocked()
        this.dek = dek
        this.dbKey = dbKey
        _state.value = VaultState.Unlocked(database)
        AresLog.i(COMPONENT, "Vault unlocked")
    }

    @Synchronized
    fun lock() {
        if (_state.value is VaultState.Locked && dek == null) return
        closeLocked()
        AresLog.i(COMPONENT, "Vault locked")
    }

    /** Runs [block] with the live DEK (do not keep a reference). Null if locked. */
    @Synchronized
    fun <T> withDek(block: (ByteArray) -> T): T? = dek?.let(block)

    fun databaseOrNull(): VaultDatabase? = (_state.value as? VaultState.Unlocked)?.database

    private fun closeLocked() {
        (_state.value as? VaultState.Unlocked)?.database?.close()
        _state.value = VaultState.Locked
        SecureBytes.wipe(dek, dbKey)
        dek = null
        dbKey = null
    }

    private companion object {
        const val COMPONENT = "Session"
    }
}
