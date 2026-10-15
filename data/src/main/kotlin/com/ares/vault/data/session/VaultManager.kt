package com.ares.vault.data.session

import android.content.Context
import com.ares.vault.core.crypto.Argon2Params
import com.ares.vault.core.crypto.AuthenticationFailedException
import com.ares.vault.core.crypto.Kdf
import com.ares.vault.core.crypto.KdfCalibrator
import com.ares.vault.core.crypto.SecureBytes
import com.ares.vault.core.crypto.VaultHeader
import com.ares.vault.core.crypto.VaultKeyHierarchy
import com.ares.vault.data.AresLog
import com.ares.vault.data.crypto.VaultHeaderStore
import com.ares.vault.data.db.SettingsEntity
import com.ares.vault.data.db.VaultDatabase
import com.ares.vault.data.keystore.BiometricKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
) {
    private val keys = VaultKeyHierarchy(kdf)
    private val mutex = Mutex()

    val state: StateFlow<VaultState> get() = session.state

    fun vaultExists(): Boolean = headerStore.exists()

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
            session.databaseOrNull()?.settings()?.upsert(SettingsEntity())
            calibration
        }
    }

    suspend fun unlockWithPassword(password: CharArray): UnlockResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val header = headerStore.read() ?: return@withContext UnlockResult.NoVault
            val dek = try {
                keys.unlock(header, password)
            } catch (_: AuthenticationFailedException) {
                AresLog.w(COMPONENT, "Master password rejected")
                return@withContext UnlockResult.WrongPassword
            }
            openDatabase(dek)
            UnlockResult.Success
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
            UnlockResult.Success
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
            val dek = try {
                keys.unlock(header, current)
            } catch (_: AuthenticationFailedException) {
                return@withContext UnlockResult.WrongPassword
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

    /** Irreversibly deletes the vault (header, database, biometric key). */
    suspend fun destroy() = mutex.withLock {
        withContext(Dispatchers.IO) {
            session.lock()
            headerStore.delete()
            VaultDatabase.delete(context)
            biometricKeys.deleteKey()
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
