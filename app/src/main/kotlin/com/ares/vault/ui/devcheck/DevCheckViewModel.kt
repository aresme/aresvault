package com.ares.vault.ui.devcheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ares.vault.core.crypto.SecureBytes
import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.data.keystore.BiometricKeyStore
import com.ares.vault.data.session.VaultManager
import com.ares.vault.data.session.VaultSession
import com.ares.vault.data.session.VaultState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * Temporary Phase 1 screen for exercising the crypto core on a real device.
 * Replaced by onboarding / lock / vault UI in Phase 3.
 */
@HiltViewModel
class DevCheckViewModel @Inject constructor(
    private val manager: VaultManager,
    private val session: VaultSession,
) : ViewModel() {

    data class UiState(
        val vaultExists: Boolean = false,
        val unlocked: Boolean = false,
        val hasBiometric: Boolean = false,
        val entryCount: Int = 0,
        val busy: Boolean = false,
        val log: List<String> = emptyList(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { session.state.collect { refresh() } }
    }

    fun create(password: CharArray) = work("Create vault") {
        if (password.size < 10) {
            log("Use at least 10 characters")
            return@work
        }
        val r = manager.create(password)
        log("Created. Argon2id ${r.params.canonical()} took ${r.measuredMillis} ms")
    }

    fun unlock(password: CharArray) = work("Unlock") {
        val start = System.nanoTime()
        val result = manager.unlockWithPassword(password)
        log("Unlock: $result in ${(System.nanoTime() - start) / 1_000_000} ms")
    }

    fun biometricUnlockCipher(): Cipher? = when (val r = manager.biometricUnlockCipher()) {
        is BiometricKeyStore.CipherResult.Ready -> r.cipher
        BiometricKeyStore.CipherResult.Invalidated -> {
            log("Fingerprint key invalidated (new fingerprint?). Use master password, then re-enable.")
            refresh()
            null
        }
        BiometricKeyStore.CipherResult.Missing -> {
            log("Fingerprint unlock not enabled")
            null
        }
    }

    fun unlockWithBiometric(cipher: Cipher) = work("Biometric unlock") {
        log("Biometric unlock: ${manager.unlockWithBiometric(cipher)}")
    }

    fun enrollCipher(): Cipher = manager.biometricEnrollCipher()

    fun completeEnrollment(cipher: Cipher) = work("Enable fingerprint") {
        manager.completeBiometricEnrollment(cipher)
        log("Fingerprint unlock enabled")
    }

    fun addSample() = work("Add entry") {
        val db = session.databaseOrNull() ?: return@work
        val now = System.currentTimeMillis()
        val pw = SecureBytes.hex(SecureBytes.random(8))
        db.credentials().insert(
            CredentialEntity(
                id = UUID.randomUUID().toString(), title = "Sample $now", username = "user@example.com",
                password = pw, webDomain = "example.com", createdAt = now, updatedAt = now,
            ),
        )
        log("Inserted sample entry")
    }

    fun changePassword(current: CharArray, new: CharArray) = work("Change password") {
        log("Change password: ${manager.changePassword(current, new)}")
    }

    fun lock() {
        manager.lock()
        log("Locked")
    }

    fun destroy() = work("Destroy") {
        manager.destroy()
        log("Vault destroyed")
    }

    fun log(line: String) = _ui.update { it.copy(log = (listOf(line) + it.log).take(30)) }

    private fun work(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                block()
            } catch (e: Exception) {
                log("$label failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                _ui.update { it.copy(busy = false) }
                refresh()
            }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val db = session.databaseOrNull()
            val count = db?.credentials()?.count() ?: 0
            _ui.update {
                it.copy(
                    vaultExists = manager.vaultExists(),
                    unlocked = session.state.value is VaultState.Unlocked,
                    hasBiometric = manager.vaultExists() && manager.hasBiometricSlot(),
                    entryCount = count,
                )
            }
        }
    }
}

