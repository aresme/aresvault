package com.ares.vault.data.session

import android.content.Context
import com.ares.vault.core.crypto.AesGcm
import com.ares.vault.core.crypto.Argon2Params
import com.ares.vault.core.crypto.AuthenticationFailedException
import com.ares.vault.core.crypto.Kdf
import com.ares.vault.core.crypto.KdfCalibrator
import com.ares.vault.core.crypto.SecureBytes
import com.ares.vault.core.crypto.SqlCipherKey
import com.ares.vault.core.crypto.VaultHeader
import com.ares.vault.core.crypto.VaultKeyHierarchy
import com.ares.vault.core.policy.BruteForceGuard
import com.ares.vault.data.AresLog
import com.ares.vault.data.autofill.AutofillIndexStore
import com.ares.vault.data.crypto.VaultHeaderStore
import com.ares.vault.data.db.SettingsEntity
import com.ares.vault.data.db.VaultDatabase
import com.ares.vault.data.keystore.BiometricKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UnlockResult {
    data object Success : UnlockResult
    /** Wrong password, or the header was tampered with (indistinguishable by design). */
    data object WrongPassword : UnlockResult
    data object NoVault : UnlockResult
    /** Too many wrong attempts; try again after [remainingMillis]. */
    data class LockedOut(val remainingMillis: Long) : UnlockResult
}

/**
 * Entry point for vault lifecycle: create, unlock (password / biometric), lock,
 * change password, and biometric enrolment. All slow work runs off the main thread.
 */
@Singleton
class VaultManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val kdf: Kdf,
    private val headerStore: VaultHeaderStore,
    private val biometricKeys: BiometricKeyStore,
    private val session: VaultSession,
    private val guard: BruteForceGuard,
    private val indexStore: AutofillIndexStore,
) {
    private val keys = VaultKeyHierarchy(kdf)
    private val mutex = Mutex()
    private val _exists = MutableStateFlow(headerStore.exists())

    val state: StateFlow<VaultState> get() = session.state

    /** Whether a vault exists; flips to false at the *start* of [destroy]. */
    val exists: StateFlow<Boolean> = _exists.asStateFlow()

    fun vaultExists(): Boolean = _exists.value

    fun hasBiometricSlot(): Boolean = headerStore.read()?.biometricSlot != null

    /** Benchmarks Argon2id, creates the key hierarchy, writes the header and opens the DB. */
    suspend fun create(password: CharArray): KdfCalibrator.Result = mutex.withLock {
        withContext(Dispatchers.Default) {
            check(!headerStore.exists()) { "Vault already exists" }
            val calibration = KdfCalibrator(kdf).calibrate()
            AresLog.i(
                COMPONENT,
                "KDF calibrated: ${calibration.params.canonical()} in ${calibration.measuredMillis} ms",
            )
            // Stale files from a previous install/reset must not survive into a new vault.
            VaultDatabase.delete(context)
            biometricKeys.deleteKey()

            val created = keys.create(password, calibration.params)
            headerStore.write(created.header)
            openDatabase(created.dek)
            _exists.value = true
            session.databaseOrNull()?.settings()?.upsert(SettingsEntity())
            calibration
        }
    }

    suspend fun unlockWithPassword(password: CharArray): UnlockResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val header = headerStore.read() ?: return@withContext UnlockResult.NoVault
            val dek = when (val r = verifyPassword(header, password)) {
                is Verified.Ok -> r.dek
                is Verified.Failed -> return@withContext r.result
            }
            openDatabaseRecovering(dek, password)
            UnlockResult.Success
        }
    }

    /**
     * Opens the DB; if that fails and an interrupted key rotation left a pending header,
     * finishes the rotation with it (the DB was already re-keyed to the pending DEK).
     */
    private fun openDatabaseRecovering(dek: ByteArray, password: CharArray) {
        val pending = headerStore.readPending()
        if (pending == null) {
            openDatabase(dek)
            return
        }
        try {
            openDatabase(dek)
            headerStore.deletePending() // crashed before the re-key: old key is still right
            AresLog.w(COMPONENT, "Discarded unfinished key rotation (database not yet re-keyed)")
        } catch (e: Exception) {
            val newDek = try {
                keys.unlock(pending, password)
            } catch (_: AuthenticationFailedException) {
                throw e
            }
            openDatabase(newDek)
            headerStore.write(pending)
            headerStore.deletePending()
            biometricKeys.deleteKey()
            AresLog.w(COMPONENT, "Completed interrupted database key rotation")
        }
    }

    /**
     * Spec follow-up (SECURITY.md): generates a new DEK and re-keys the SQLCipher database in
     * place, so an old copy of the header plus an old password can no longer open it.
     * Crash-safe order: write pending header → re-key DB → promote header. The biometric slot
     * wraps the old DEK, so fingerprint unlock must be re-enabled afterwards.
     */
    suspend fun rotateDatabaseKey(password: CharArray): UnlockResult = mutex.withLock {
        withContext(Dispatchers.Default + NonCancellable) {
            val header = headerStore.read() ?: return@withContext UnlockResult.NoVault
            val db = session.databaseOrNull() ?: return@withContext UnlockResult.NoVault
            when (val r = verifyPassword(header, password)) {
                is Verified.Ok -> SecureBytes.wipe(r.dek)
                is Verified.Failed -> return@withContext r.result
            }
            val newDek = SecureBytes.random(AesGcm.KEY_BYTES)
            val params = KdfCalibrator(kdf).calibrate().params
            val newHeader = keys.changePassword(header.copy(biometricSlot = null), newDek, password, params)
            headerStore.writePending(newHeader)
            val newDbKey = SqlCipherKey.rawKey(newDek)
            (db.openHelper.writableDatabase as net.zetetic.database.sqlcipher.SQLiteDatabase).changePassword(newDbKey)
            headerStore.write(newHeader)
            headerStore.deletePending()
            session.replaceKeys(newDek, newDbKey)
            biometricKeys.deleteKey()
            AresLog.i(COMPONENT, "Database key rotated")
            UnlockResult.Success
        }
    }

    /** Remaining lockout, for the lock screen countdown. */
    fun lockoutRemainingMillis(): Long = guard.remainingMillis()

    fun failedAttempts(): Int = guard.failures()

    private sealed interface Verified {
        class Ok(val dek: ByteArray) : Verified
        class Failed(val result: UnlockResult) : Verified
    }

    /** Password check with brute-force throttling (spec §4.3). */
    private fun verifyPassword(header: VaultHeader, password: CharArray): Verified {
        val remaining = guard.remainingMillis()
        if (remaining > 0) return Verified.Failed(UnlockResult.LockedOut(remaining))
        return try {
            Verified.Ok(keys.unlock(header, password)).also { guard.reset() }
        } catch (_: AuthenticationFailedException) {
            val delay = guard.recordFailure()
            AresLog.w(COMPONENT, "Master password rejected (attempt ${guard.failures()})")
            Verified.Failed(if (delay > 0) UnlockResult.LockedOut(delay) else UnlockResult.WrongPassword)
        }
    }

    /** Cipher to hand to BiometricPrompt for unlocking, or why it can't be used. */
    fun biometricUnlockCipher(): BiometricKeyStore.CipherResult {
        val slot = headerStore.read()?.biometricSlot ?: return BiometricKeyStore.CipherResult.Missing
        val result = biometricKeys.decryptCipher(slot.iv)
        if (result is BiometricKeyStore.CipherResult.Invalidated) {
            // The slot can never be opened again; drop it so the UI offers re-enrolment.
            headerStore.read()?.let { headerStore.write(it.copy(biometricSlot = null)) }
        }
        return result
    }

    /** Call with the cipher returned by an authenticated BiometricPrompt. */
    suspend fun unlockWithBiometric(authenticatedCipher: Cipher): UnlockResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val header = headerStore.read() ?: return@withContext UnlockResult.NoVault
            val slot = header.biometricSlot ?: return@withContext UnlockResult.WrongPassword
            val dek = try {
                BiometricKeyStore.open(authenticatedCipher, slot, VaultHeader.biometricSlotAad())
            } catch (e: AuthenticationFailedException) {
                AresLog.w(COMPONENT, "Biometric slot failed to decrypt", e)
                return@withContext UnlockResult.WrongPassword
            }
            openDatabase(dek)
            guard.reset()
            UnlockResult.Success
        }
    }

    /**
     * Re-checks the master password without changing state (used before sensitive actions
     * like plaintext export or destroying the vault). Throttled like an unlock.
     */
    suspend fun verifyMasterPassword(password: CharArray): UnlockResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val header = headerStore.read() ?: return@withContext UnlockResult.NoVault
            when (val r = verifyPassword(header, password)) {
                is Verified.Ok -> {
                    SecureBytes.wipe(r.dek)
                    UnlockResult.Success
                }
                is Verified.Failed -> r.result
            }
        }
    }

    /** Creates a fresh biometric key; pass the cipher to BiometricPrompt, then [completeBiometricEnrollment]. */
    fun biometricEnrollCipher(): Cipher = biometricKeys.newEncryptCipher()

    suspend fun completeBiometricEnrollment(authenticatedCipher: Cipher) = mutex.withLock {
        withContext(Dispatchers.Default) {
            val header = requireNotNull(headerStore.read())
            val slot = checkNotNull(
                session.withDek { dek ->
                    BiometricKeyStore.seal(authenticatedCipher, dek, VaultHeader.biometricSlotAad())
                },
            ) { "Vault must be unlocked" }
            headerStore.write(header.copy(biometricSlot = slot))
            AresLog.i(COMPONENT, "Biometric unlock enabled")
        }
    }

    suspend fun disableBiometric() = mutex.withLock {
        withContext(Dispatchers.Default) {
            headerStore.read()?.let { headerStore.write(it.copy(biometricSlot = null)) }
            biometricKeys.deleteKey()
        }
    }

    /**
     * Verifies [current], then re-wraps the DEK under [new] with a fresh salt and
     * re-calibrated params. The database itself is not re-encrypted.
     */
    suspend fun changePassword(current: CharArray, new: CharArray): UnlockResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val header = headerStore.read() ?: return@withContext UnlockResult.NoVault
            val dek = when (val r = verifyPassword(header, current)) {
                is Verified.Ok -> r.dek
                is Verified.Failed -> return@withContext r.result
            }
            try {
                val params = KdfCalibrator(kdf).calibrate().params
                headerStore.write(keys.changePassword(header, dek, new, params))
            } finally {
                SecureBytes.wipe(dek)
            }
            AresLog.i(COMPONENT, "Master password changed")
            UnlockResult.Success
        }
    }

    fun lock() = session.lock()

    /**
     * Irreversibly deletes the vault (header, database, biometric key). Non-cancellable:
     * locking tears down the calling UI, which must not abort a half-finished deletion.
     */
    suspend fun destroy() = mutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            _exists.value = false
            session.lock()
            headerStore.delete()
            indexStore.delete()
            VaultDatabase.delete(context)
            biometricKeys.deleteKey()
            guard.reset()
            AresLog.w(COMPONENT, "Vault destroyed")
        }
    }

    fun currentParams(): Argon2Params? = headerStore.read()?.kdf

    private fun openDatabase(dek: ByteArray) {
        val (db, dbKey) = try {
            VaultDatabase.open(context, dek)
        } catch (e: Exception) {
            SecureBytes.wipe(dek)
            throw e
        }
        session.open(db, dek, dbKey)
    }

    private companion object {
        const val COMPONENT = "VaultManager"
    }
}
