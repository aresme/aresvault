package com.ares.vault.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credentials ORDER BY favorite DESC, title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials WHERE id = :id")
    fun observe(id: String): Flow<CredentialEntity?>

    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun get(id: String): CredentialEntity?

    @Query("SELECT * FROM credentials WHERE appPackage = :packageName")
    suspend fun findByPackage(packageName: String): List<CredentialEntity>

    /** Candidates for a registrable domain; exact eTLD+1 matching happens in Kotlin. */
    @Query(
        "SELECT * FROM credentials WHERE webDomain = :domain OR webDomain LIKE '%.' || :domain",
    )
    suspend fun findByDomain(domain: String): List<CredentialEntity>

    @Query("SELECT COUNT(*) FROM credentials")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: CredentialEntity)

    @Update
    suspend fun update(entity: CredentialEntity)

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE credentials SET lastUsedAt = :at WHERE id = :id")
    suspend fun markUsed(id: String, at: Long)

    @Query("SELECT * FROM password_history WHERE credentialId = :id ORDER BY replacedAt DESC, id DESC")
    fun observeHistory(id: String): Flow<List<PasswordHistoryEntity>>

    @Insert
    suspend fun insertHistory(entry: PasswordHistoryEntity)

    @Query(
        """DELETE FROM password_history WHERE credentialId = :id AND id NOT IN (
            SELECT id FROM password_history WHERE credentialId = :id
            ORDER BY replacedAt DESC, id DESC LIMIT :keep)""",
    )
    suspend fun trimHistory(id: String, keep: Int)

    /** Updates an entry; if the password changed, the old one goes to history (max 5). */
    @Transaction
    suspend fun updateKeepingHistory(updated: CredentialEntity) {
        val existing = get(updated.id) ?: return
        if (existing.password != updated.password) {
            insertHistory(
                PasswordHistoryEntity(
                    credentialId = existing.id,
                    password = existing.password,
                    replacedAt = updated.updatedAt,
                ),
            )
            trimHistory(existing.id, HISTORY_SIZE)
        }
        update(updated)
    }

    companion object {
        const val HISTORY_SIZE = 5
    }
}
