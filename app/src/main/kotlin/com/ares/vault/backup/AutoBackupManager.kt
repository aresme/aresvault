package com.ares.vault.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.ares.vault.core.backup.BackupKey
import com.ares.vault.core.crypto.Argon2Params
import com.ares.vault.core.crypto.SecureBytes
import com.ares.vault.data.AresLog
import com.ares.vault.data.backup.BackupRepository
import com.ares.vault.data.session.SettingsRepository
import com.ares.vault.data.session.VaultSession
import com.ares.vault.data.session.VaultState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Pure helpers (unit-tested). */
object AutoBackupFiles {
    const val PREFIX = "AresVault-auto-"
    const val SUFFIX = ".aresvault"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun name(now: LocalDateTime): String = PREFIX + STAMP.format(now) + SUFFIX

    /** Our automatic files beyond the newest [keep] (names sort by time); manual backups untouched. */
    fun toDelete(names: List<String>, keep: Int): List<String> =
        names.filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }.sortedDescending().drop(keep.coerceAtLeast(1))

    /** "primary:Download/AresVault" → "Download/AresVault". */
    fun displayName(treeUri: String): String =
        Uri.decode(Uri.parse(treeUri).lastPathSegment.orEmpty()).substringAfter(':').ifEmpty { "chosen folder" }
}

/**
 * Writes an encrypted backup to the user's chosen folder whenever vault content changes
 * (logins, passkeys, 2FA), keeping the newest N. Runs only while the vault is unlocked; the key
 * is Argon2id(backup password) stored inside the encrypted DB, so no password prompt is needed
 * and the files restore with the backup password. No network: the folder can be one a cloud
 * app syncs, but AresVault itself only writes a local file.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class AutoBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: VaultSession,
    private val backups: BackupRepository,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun start() {
        scope.launch {
            session.state.flatMapLatest { state ->
                if (state !is VaultState.Unlocked) {
                    emptyFlow()
                } else {
                    combine(state.database.credentials().observeAll(), state.database.passkeys().observeAll()) { creds, keys ->
                        // Ignore "last used" timestamps, which change on every autofill.
                        creds.map { it.copy(lastUsedAt = null) }.hashCode() * 31 + keys.map { it.copy(lastUsedAt = null, signCount = 0) }.hashCode()
                    }.distinctUntilChanged().drop(1).catch { }
                }
            }.debounce(3_000).collect { runIfEnabled() }
        }
    }

    private suspend fun runIfEnabled() {
        if (settings.get()?.autoBackupEnabled == true) runNow()
    }

    /** Writes one backup now. Returns an error message or null. */
    suspend fun runNow(): String? = mutex.withLock {
        val s = settings.get() ?: return "Vault is locked"
        val folder = s.autoBackupFolder ?: return "No folder chosen"
        val key = key(s.autoBackupKey, s.autoBackupSalt, s.autoBackupParams) ?: return "Backup key missing — set up again"
        val error = try {
            val bytes = backups.exportWithKey(key)
            val tree = Uri.parse(folder)
            val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(context.contentResolver, dir, "application/octet-stream", AutoBackupFiles.name(LocalDateTime.now()))
                ?: error("Couldn't create the file")
            context.contentResolver.openOutputStream(doc, "w")?.use { it.write(bytes) } ?: error("Couldn't write the file")
            prune(tree, s.autoBackupKeep)
            AresLog.i("AutoBackup", "Automatic backup written")
            null
        } catch (e: Exception) {
            AresLog.w("AutoBackup", "Automatic backup failed", e)
            when (e) {
                is SecurityException -> "Lost access to the folder — choose it again"
                else -> e.message ?: "Backup failed"
            }
        } finally {
            SecureBytes.wipe(key.key)
        }
        settings.update {
            if (error == null) it.copy(lastAutoBackupAt = System.currentTimeMillis(), lastAutoBackupError = null)
            else it.copy(lastAutoBackupError = error)
        }
        error
    }

    /** One-time setup: keep folder access, derive the key from the backup password, back up now. */
    suspend fun configure(folder: Uri, password: CharArray, keep: Int): String? {
        try {
            context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            return "Android didn't allow access to that folder"
        }
        val key = try {
            backups.deriveBackupKey(password)
        } finally {
            SecureBytes.wipe(password)
        }
        val b64 = Base64.getEncoder()
        settings.update {
            it.copy(
                autoBackupEnabled = true,
                autoBackupFolder = folder.toString(),
                autoBackupKey = b64.encodeToString(key.key),
                autoBackupSalt = b64.encodeToString(key.salt),
                autoBackupParams = Json.encodeToString(Argon2Params.serializer(), key.params),
                autoBackupKeep = keep,
                lastAutoBackupError = null,
            )
        }
        SecureBytes.wipe(key.key)
        return runNow()
    }

    suspend fun setKeep(keep: Int) = settings.update { it.copy(autoBackupKeep = keep) }

    suspend fun disable() {
        settings.get()?.autoBackupFolder?.let { f ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(f), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        settings.update {
            it.copy(autoBackupEnabled = false, autoBackupFolder = null, autoBackupKey = null, autoBackupSalt = null, autoBackupParams = null, lastAutoBackupError = null)
        }
    }

    private fun prune(tree: Uri, keep: Int) {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val byName = mutableMapOf<String, String>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c -> while (c.moveToNext()) byName[c.getString(1)] = c.getString(0) }
        for (name in AutoBackupFiles.toDelete(byName.keys.toList(), keep)) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, byName.getValue(name))) }
        }
    }

    private fun key(key: String?, salt: String?, params: String?): BackupKey? {
        if (key == null || salt == null || params == null) return null
        val d = Base64.getDecoder()
        return BackupKey(d.decode(key), d.decode(salt), Json.decodeFromString(Argon2Params.serializer(), params))
    }
}
