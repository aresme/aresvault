package com.ares.vault.core.backup

import com.ares.vault.core.crypto.AesGcm
import com.ares.vault.core.crypto.Argon2Params
import com.ares.vault.core.crypto.Base64ByteArraySerializer
import com.ares.vault.core.crypto.Kdf
import com.ares.vault.core.crypto.Sealed
import com.ares.vault.core.crypto.SecureBytes
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.SecureRandom

@Serializable
data class BackupHistory(val password: String, val replacedAt: Long)

/** One credential in a backup. Mirrors the DB row plus its password history. */
@Serializable
data class BackupEntry(
    val id: String,
    val title: String,
    val username: String,
    val password: String,
    val notes: String = "",
    val appPackage: String? = null,
    val appCertSha256: String? = null,
    val webDomain: String? = null,
    val extraUrls: List<String> = emptyList(),
    val folder: String? = null,
    val favorite: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
    val history: List<BackupHistory> = emptyList(),
    /** Added in Phase 8; older backups simply don't have it. */
    val totpUri: String? = null,
)

@Serializable
data class BackupPasskey(
    val id: String, val credentialId: String, val rpId: String, val rpName: String, val userHandle: String,
    val userName: String, val userDisplayName: String, val privateKey: String, val signCount: Long,
    val createdAt: Long, val lastUsedAt: Long? = null,
)

@Serializable
data class BackupPayload(
    val version: Int = 1,
    val exportedAt: Long,
    val entries: List<BackupEntry>,
    /** Added in Phase 9; older backups have none. */
    val passkeys: List<BackupPasskey> = emptyList(),
)

/** A derived backup key plus the salt/params needed to re-derive it from the password. */
class BackupKey(val key: ByteArray, val salt: ByteArray, val params: Argon2Params) {
    companion object {
        internal fun derive(codec: BackupCodec, password: CharArray, params: Argon2Params): BackupKey {
            val salt = codec.newSalt()
            return BackupKey(codec.deriveRaw(password, salt, params), salt, params)
        }
    }
}

class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** On-disk `.aresvault` container. Everything except [sealed] is public metadata. */
@Serializable
private class BackupFile(
    val format: String,
    val version: Int,
    val kdf: Argon2Params,
    @Serializable(with = Base64ByteArraySerializer::class) val salt: ByteArray,
    val sealed: Sealed,
)

/**
 * Encrypted backup (spec §6.4): AES-256-GCM under Argon2id(backup password, random salt).
 * The backup key is unrelated to the vault's KEK/DEK, so a backup can be restored on a new
 * phone with only its backup password. Format, version, KDF params and salt are bound as
 * AAD, so they can't be swapped or downgraded.
 */
class BackupCodec(private val kdf: Kdf, private val random: SecureRandom = SecureRandom()) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encrypt(payload: BackupPayload, password: CharArray, params: Argon2Params = Argon2Params.DEFAULT): ByteArray {
        val key = BackupKey.derive(this, password, params)
        try {
            return encryptWithKey(payload, key)
        } finally {
            SecureBytes.wipe(key.key)
        }
    }

    /**
     * Automatic backups: encrypt with a key derived earlier from the backup password (kept in
     * the encrypted vault). The file records the same salt and params, so restoring only needs
     * the password.
     */
    fun encryptWithKey(payload: BackupPayload, key: BackupKey): ByteArray {
        val plain = json.encodeToString(BackupPayload.serializer(), payload).toByteArray()
        try {
            val sealed = AesGcm.seal(key.key, plain, aad(VERSION, key.params, key.salt), random)
            return json.encodeToString(BackupFile.serializer(), BackupFile(FORMAT, VERSION, key.params, key.salt, sealed)).toByteArray()
        } finally {
            SecureBytes.wipe(plain)
        }
    }

    /** Argon2id(password, fresh salt) → key for [encryptWithKey]. Caller wipes [BackupKey.key]. */
    fun deriveKey(password: CharArray, params: Argon2Params = Argon2Params.DEFAULT): BackupKey =
        BackupKey.derive(this, password, params)

    internal fun deriveRaw(password: CharArray, salt: ByteArray, params: Argon2Params) = derive(password, salt, params)
    internal fun newSalt() = SecureBytes.random(SALT_BYTES, random)

    /** @throws InvalidBackupException not a backup / unsupported version
     *  @throws com.ares.vault.core.crypto.AuthenticationFailedException wrong password or tampered */
    fun decrypt(bytes: ByteArray, password: CharArray): BackupPayload {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            throw InvalidBackupException("Not an AresVault backup", e)
        } catch (e: IllegalArgumentException) {
            throw InvalidBackupException("Not an AresVault backup", e)
        }
        if (file.format != FORMAT) throw InvalidBackupException("Not an AresVault backup")
        if (file.version != VERSION) throw InvalidBackupException("Unsupported backup version ${file.version}")
        if (file.kdf.memoryKiB > MAX_MEMORY_KIB || file.kdf.iterations > MAX_ITERATIONS) {
            // A hostile file must not make us allocate gigabytes or spin forever.
            throw InvalidBackupException("Backup key-derivation settings are out of range")
        }
        val key = derive(password, file.salt, file.kdf)
        val plain = try {
            AesGcm.open(key, file.sealed, aad(file.version, file.kdf, file.salt))
        } finally {
            SecureBytes.wipe(key)
        }
        try {
            return json.decodeFromString(BackupPayload.serializer(), plain.decodeToString())
        } catch (e: SerializationException) {
            throw InvalidBackupException("Backup contents are corrupt", e)
        } finally {
            SecureBytes.wipe(plain)
        }
    }

    private fun derive(password: CharArray, salt: ByteArray, params: Argon2Params): ByteArray {
        val bytes = SecureBytes.utf8(password)
        try {
            return kdf.derive(bytes, salt, params)
        } finally {
            SecureBytes.wipe(bytes)
        }
    }

    private fun aad(version: Int, params: Argon2Params, salt: ByteArray) =
        "$FORMAT|v$version|${params.canonical()}|".toByteArray(Charsets.US_ASCII) + salt

    companion object {
        const val FORMAT = "aresvault-backup"
        const val VERSION = 1
        const val FILE_EXTENSION = "aresvault"
        private const val SALT_BYTES = 16
        private const val MAX_MEMORY_KIB = 1024 * 1024
        private const val MAX_ITERATIONS = 64
    }
}
