package com.ares.vault.data.backup

import com.ares.vault.core.backup.BackupCodec
import com.ares.vault.core.backup.BackupEntry
import com.ares.vault.core.backup.BackupHistory
import com.ares.vault.core.backup.BackupPayload
import com.ares.vault.core.backup.ImportedLogin
import com.ares.vault.core.backup.PasswordCsv
import com.ares.vault.core.crypto.Kdf
import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.data.db.PasswordHistoryEntity
import com.ares.vault.data.repo.CredentialRepository
import com.ares.vault.data.session.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ImportReport(val added: Int, val updated: Int, val skipped: Int)

/** Spec §6.4: encrypted backup, Google/Firefox CSV import, plaintext CSV export. */
@Singleton
class BackupRepository @Inject constructor(
    private val session: VaultSession,
    private val repository: CredentialRepository,
    kdf: Kdf,
) {
    private val codec = BackupCodec(kdf)

    private fun dao() = session.databaseOrNull()?.credentials() ?: throw CredentialRepository.VaultLockedException()

    suspend fun exportEncrypted(backupPassword: CharArray): ByteArray = withContext(Dispatchers.Default) {
        codec.encrypt(payload(), backupPassword)
    }

    /** Plaintext Google-format CSV. Callers must re-verify the master password first. */
    suspend fun exportCsv(): String = withContext(Dispatchers.Default) { PasswordCsv.export(payload().entries) }

    /** Restores a backup, merging by entry id: unknown ids are added, newer copies win. */
    suspend fun importEncrypted(bytes: ByteArray, backupPassword: CharArray): ImportReport = withContext(Dispatchers.Default) {
        val payload = codec.decrypt(bytes, backupPassword)
        var added = 0
        var updated = 0
        var skipped = 0
        val dao = dao()
        for (e in payload.entries) {
            val existing = dao.get(e.id)
            when {
                existing == null -> {
                    dao.insert(e.toEntity())
                    e.history.forEach { h -> dao.insertHistory(PasswordHistoryEntity(credentialId = e.id, password = h.password, replacedAt = h.replacedAt)) }
                    added++
                }
                existing.updatedAt < e.updatedAt -> {
                    dao.updateKeepingHistory(e.toEntity())
                    updated++
                }
                else -> skipped++
            }
        }
        ImportReport(added, updated, skipped)
    }

    /** Imports CSV logins, skipping exact duplicates (same site/app, username and password). */
    suspend fun importLogins(logins: List<ImportedLogin>): ImportReport = withContext(Dispatchers.Default) {
        val existing = repository.all()
        var added = 0
        var skipped = 0
        val now = System.currentTimeMillis()
        val dao = dao()
        for (l in logins) {
            val duplicate = existing.any {
                it.username == l.username && it.password == l.password &&
                    ((l.webDomain != null && it.webDomain == l.webDomain) || (l.appPackage != null && it.appPackage == l.appPackage))
            }
            if (duplicate) {
                skipped++
                continue
            }
            dao.insert(
                CredentialEntity(
                    id = UUID.randomUUID().toString(), title = l.title, username = l.username, password = l.password,
                    notes = l.notes, appPackage = l.appPackage, appCertSha256 = l.appCertSha256, webDomain = l.webDomain,
                    extraUrls = l.extraUrls, folder = "Imported", createdAt = now, updatedAt = now,
                ),
            )
            added++
        }
        ImportReport(added, 0, skipped)
    }

    private suspend fun payload(): BackupPayload {
        val dao = dao()
        val entries = dao.getAll().map { c ->
            BackupEntry(
                id = c.id, title = c.title, username = c.username, password = c.password, notes = c.notes,
                appPackage = c.appPackage, appCertSha256 = c.appCertSha256, webDomain = c.webDomain,
                extraUrls = c.extraUrls, folder = c.folder, favorite = c.favorite, createdAt = c.createdAt,
                updatedAt = c.updatedAt, lastUsedAt = c.lastUsedAt, totpUri = c.totpUri,
                history = dao.getHistory(c.id).map { BackupHistory(it.password, it.replacedAt) },
            )
        }
        return BackupPayload(exportedAt = System.currentTimeMillis(), entries = entries)
    }

    private fun BackupEntry.toEntity() = CredentialEntity(
        id = id, title = title, username = username, password = password, notes = notes, appPackage = appPackage,
        appCertSha256 = appCertSha256, webDomain = webDomain, extraUrls = extraUrls, folder = folder,
        favorite = favorite, createdAt = createdAt, updatedAt = updatedAt, lastUsedAt = lastUsedAt, totpUri = totpUri,
    )
}
