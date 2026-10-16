package com.ares.vault.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A stored login. The whole database file is encrypted by SQLCipher, so fields are
 * stored as-is inside it. App identities keep both package and signing-cert digest
 * (spec §4.4); web identities keep the registrable domain.
 */
@Entity(
    tableName = "credentials",
    indices = [Index("appPackage"), Index("webDomain")],
)
data class CredentialEntity(
    @PrimaryKey val id: String,
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
    /** 2FA: canonical `otpauth://totp/...` URI incl. the secret (encrypted with the DB). */
    val totpUri: String? = null,
)

@Entity(
    tableName = "password_history",
    foreignKeys = [
        ForeignKey(
            entity = CredentialEntity::class,
            parentColumns = ["id"],
            childColumns = ["credentialId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("credentialId")],
)
data class PasswordHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val credentialId: String,
    val password: String,
    val replacedAt: Long,
)

/** Single-row settings table, stored inside the encrypted database (spec §5). */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** Serialised generator config; owned by the generator feature. Empty = defaults. */
    val generatorConfig: String = "",
    val autoLockSeconds: Int = 120,
    val lockOnScreenOff: Boolean = true,
    val clipboardClearSeconds: Int = 30,
    val biometricEnabled: Boolean = false,
    /** Spec §6.3 locked-state account labels; see SECURITY.md. */
    val showAccountsWhenLocked: Boolean = true,
    /** Spec §1: picking an account asks for the fingerprint even when the vault is unlocked. */
    @ColumnInfo(defaultValue = "1")
    val requireAuthToFill: Boolean = true,

    // Automatic backups (stored here, inside the encrypted DB, because the key must stay secret).
    @ColumnInfo(defaultValue = "0")
    val autoBackupEnabled: Boolean = false,
    /** Storage Access Framework tree URI of the chosen folder. */
    val autoBackupFolder: String? = null,
    /** Base64 key = Argon2id(backup password, salt); lets backups run without retyping it. */
    val autoBackupKey: String? = null,
    val autoBackupSalt: String? = null,
    /** JSON Argon2Params used for the key (written into each file so the password restores it). */
    val autoBackupParams: String? = null,
    @ColumnInfo(defaultValue = "5")
    val autoBackupKeep: Int = 5,
    val lastAutoBackupAt: Long? = null,
    val lastAutoBackupError: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
