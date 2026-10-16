package com.ares.vault.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A passkey (WebAuthn discoverable credential). The ES256 private key is stored as PKCS#8
 * inside the SQLCipher database (not the Keystore) so it is covered by encrypted backups.
 */
@Entity(tableName = "passkeys", indices = [Index("rpId"), Index(value = ["credentialId"], unique = true)])
data class PasskeyEntity(
    @PrimaryKey val id: String,
    /** base64url credential id, as sent to/received from relying parties. */
    val credentialId: String,
    val rpId: String,
    val rpName: String,
    /** base64url user.id (user handle) from the relying party. */
    val userHandle: String,
    val userName: String,
    val userDisplayName: String,
    /** base64 PKCS#8 EC P-256 private key. */
    val privateKey: String,
    val signCount: Long = 0,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
)

/**
 * The user approved [packageName] signed by [certSha256] to use passkeys for [rpId]. Needed
 * because the usual Digital Asset Links check requires the network, which AresVault doesn't have.
 */
@Entity(tableName = "passkey_app_links", primaryKeys = ["rpId", "packageName", "certSha256"])
data class PasskeyAppLinkEntity(
    val rpId: String,
    val packageName: String,
    val certSha256: String,
    val approvedAt: Long,
)

@Dao
interface PasskeyDao {
    @Query("SELECT * FROM passkeys ORDER BY rpId COLLATE NOCASE, userName COLLATE NOCASE")
    fun observeAll(): Flow<List<PasskeyEntity>>

    @Query("SELECT * FROM passkeys")
    suspend fun getAll(): List<PasskeyEntity>

    @Query("SELECT * FROM passkeys WHERE rpId = :rpId ORDER BY lastUsedAt DESC")
    suspend fun forRp(rpId: String): List<PasskeyEntity>

    @Query("SELECT * FROM passkeys WHERE id = :id")
    suspend fun get(id: String): PasskeyEntity?

    @Query("SELECT * FROM passkeys WHERE credentialId = :credentialId")
    suspend fun byCredentialId(credentialId: String): PasskeyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(p: PasskeyEntity)

    @Query("UPDATE passkeys SET signCount = :count, lastUsedAt = :at WHERE id = :id")
    suspend fun markUsed(id: String, count: Long, at: Long)

    @Query("DELETE FROM passkeys WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM passkey_app_links WHERE rpId = :rpId AND packageName = :pkg AND certSha256 = :cert")
    suspend fun isLinked(rpId: String, pkg: String, cert: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun link(link: PasskeyAppLinkEntity)
}
