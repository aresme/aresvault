package com.ares.vault.data.repo

import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.data.db.PasswordHistoryEntity
import com.ares.vault.data.session.VaultSession
import com.ares.vault.data.session.VaultState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Editable fields of a credential (everything except ids and timestamps). */
data class CredentialDraft(
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
    val totpUri: String? = null,
)

/**
 * The single access point for stored credentials. UI, autofill and backup go through
 * here, so storage can later sit behind a sync layer without touching callers.
 * All flows emit empty/null while the vault is locked and survive the DB closing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CredentialRepository @Inject constructor(
    private val session: VaultSession,
) {
    class VaultLockedException : IllegalStateException("Vault is locked")

    private fun dao() = session.databaseOrNull()?.credentials() ?: throw VaultLockedException()

    fun observeAll(): Flow<List<CredentialEntity>> = session.state.flatMapLatest { state ->
        when (state) {
            is VaultState.Unlocked -> state.database.credentials().observeAll().catch { emit(emptyList()) }
            VaultState.Locked -> flowOf(emptyList())
        }
    }

    fun observe(id: String): Flow<CredentialEntity?> = session.state.flatMapLatest { state ->
        when (state) {
            is VaultState.Unlocked -> state.database.credentials().observe(id).catch { emit(null) }
            VaultState.Locked -> flowOf(null)
        }
    }

    fun observeHistory(id: String): Flow<List<PasswordHistoryEntity>> = session.state.flatMapLatest { state ->
        when (state) {
            is VaultState.Unlocked -> state.database.credentials().observeHistory(id).catch { emit(emptyList()) }
            VaultState.Locked -> flowOf(emptyList())
        }
    }

    suspend fun get(id: String): CredentialEntity? = dao().get(id)

    suspend fun all(): List<CredentialEntity> = session.databaseOrNull()?.credentials()?.getAll().orEmpty()

    suspend fun findByPackage(packageName: String) = dao().findByPackage(packageName)

    suspend fun findByDomain(registrableDomain: String) = dao().findByDomain(registrableDomain)

    suspend fun create(draft: CredentialDraft, now: Long = System.currentTimeMillis()): String {
        val id = UUID.randomUUID().toString()
        dao().insert(draft.toEntity(id, createdAt = now, updatedAt = now))
        return id
    }

    /** Updates fields; a changed password moves the old one into history (max 5). */
    suspend fun update(id: String, draft: CredentialDraft, now: Long = System.currentTimeMillis()) {
        val existing = dao().get(id) ?: return
        dao().updateKeepingHistory(
            draft.toEntity(id, createdAt = existing.createdAt, updatedAt = now, lastUsedAt = existing.lastUsedAt),
        )
    }

    suspend fun setFavorite(id: String, favorite: Boolean) {
        val existing = dao().get(id) ?: return
        dao().update(existing.copy(favorite = favorite))
    }

    suspend fun markUsed(id: String, now: Long = System.currentTimeMillis()) = dao().markUsed(id, now)

    suspend fun delete(id: String) = dao().delete(id)

    private fun CredentialDraft.toEntity(id: String, createdAt: Long, updatedAt: Long, lastUsedAt: Long? = null) =
        CredentialEntity(
            id = id,
            title = title.trim(),
            username = username.trim(),
            password = password,
            notes = notes,
            appPackage = appPackage?.trim()?.ifEmpty { null },
            appCertSha256 = appCertSha256?.ifEmpty { null },
            webDomain = webDomain?.trim()?.lowercase()?.ifEmpty { null },
            extraUrls = extraUrls.map { it.trim() }.filter { it.isNotEmpty() },
            folder = folder?.trim()?.ifEmpty { null },
            favorite = favorite,
            totpUri = totpUri?.ifBlank { null },
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastUsedAt = lastUsedAt,
        )
}

fun CredentialEntity.toDraft() = CredentialDraft(
    title = title, username = username, password = password, notes = notes,
    appPackage = appPackage, appCertSha256 = appCertSha256, webDomain = webDomain,
    extraUrls = extraUrls, folder = folder, favorite = favorite, totpUri = totpUri,
)
