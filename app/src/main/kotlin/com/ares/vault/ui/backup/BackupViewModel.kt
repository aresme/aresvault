package com.ares.vault.ui.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.toRoute
import com.ares.vault.backup.AutoBackupFiles
import com.ares.vault.backup.AutoBackupManager
import com.ares.vault.backup.BackupReminderWorker
import com.ares.vault.data.session.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.ares.vault.backup.EmailBackup
import com.ares.vault.prefs.AppPrefs
import com.ares.vault.ui.nav.BackupRoute
import kotlinx.coroutines.flow.MutableSharedFlow
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ares.vault.core.backup.CsvFormatException
import com.ares.vault.core.backup.InvalidBackupException
import com.ares.vault.core.backup.PasswordCsv
import com.ares.vault.core.crypto.AuthenticationFailedException
import com.ares.vault.core.crypto.SecureBytes
import com.ares.vault.core.policy.MasterPasswordPolicy
import com.ares.vault.data.AresLog
import com.ares.vault.data.backup.BackupRepository
import com.ares.vault.data.backup.ImportReport
import com.ares.vault.data.session.AutoLockController
import com.ares.vault.data.session.UnlockResult
import com.ares.vault.data.session.VaultManager
import com.ares.vault.ui.components.formatCountdown
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backups: BackupRepository,
    private val manager: VaultManager,
    private val autoLock: AutoLockController,
    private val prefs: AppPrefs,
    private val autoBackup: AutoBackupManager,
    settingsRepo: SettingsRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    /** Automatic-backup status, from the encrypted settings row. */
    val settings = settingsRepo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var pendingFolder: Uri? = null

    /** Folder picked; next step is the backup password dialog. */
    fun folderChosen(uri: Uri?): Boolean {
        pendingFolder = uri
        return uri != null
    }

    fun configureAuto(password: String, confirm: String, keep: Int): String? {
        prepareBackupPassword(password, confirm)?.let { return it }
        val folder = pendingFolder ?: return "Choose a folder first"
        val pw = pendingPasswordCopy() ?: return "Enter a password"
        replacePendingPublic()
        run {
            autoBackup.configure(folder, pw, keep)?.let { "Automatic backups set up, but the first one failed: $it" }
                ?: "Automatic backups are on. A backup was saved to ${AutoBackupFiles.displayName(folder.toString())}"
        }
        return null
    }

    fun backUpNow() = run { autoBackup.runNow()?.let { "Backup failed: $it" } ?: "Backup saved" }

    fun setKeep(keep: Int) = viewModelScope.launch { autoBackup.setKeep(keep) }

    fun disableAuto() = run {
        autoBackup.disable()
        "Automatic backups are off. Existing backup files were kept."
    }

    /** Opened from the weekly reminder: show the email step right away. */
    val emailNow: Boolean = savedState.toRoute<BackupRoute>().emailNow

    data class EmailState(val address: String, val reminder: Boolean, val lastSentAt: Long)

    val email = MutableStateFlow(EmailState(prefs.backupEmail, prefs.emailReminderEnabled, prefs.lastEmailBackupAt))

    /** One-shot: the email intent to start. */
    val emailIntent = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    /** Returns an error, or null when saved. */
    fun saveEmail(address: String): String? {
        if (address.isNotBlank() && !EmailBackup.isValidAddress(address)) return "That doesn't look like an email address"
        prefs.backupEmail = address
        updateReminderSchedule()
        email.update { it.copy(address = prefs.backupEmail) }
        return null
    }

    fun setReminder(enabled: Boolean) {
        prefs.emailReminderEnabled = enabled
        updateReminderSchedule()
        email.update { it.copy(reminder = enabled) }
    }

    private fun updateReminderSchedule() {
        if (prefs.emailReminderEnabled && prefs.backupEmail.isNotBlank()) BackupReminderWorker.schedule(context) else BackupReminderWorker.cancel(context)
    }

    /** Uses the password stored by [prepareBackupPassword]; emits the email intent. */
    fun emailBackup() = withPending(Uri.EMPTY) { _, pw ->
        val address = prefs.backupEmail
        val bytes = backups.exportEncrypted(pw)
        val intent = EmailBackup.shareIntent(context, bytes, address, backups.itemCount())
        autoLock.holdForExternalActivity()
        prefs.lastEmailBackupAt = System.currentTimeMillis()
        email.update { it.copy(lastSentAt = prefs.lastEmailBackupAt) }
        BackupReminderWorker.dismiss(context)
        emailIntent.tryEmit(intent)
        "Email app opened — tap Send to deliver the encrypted backup to $address"
    }


    data class UiState(
        val busy: Boolean = false,
        val message: String? = null,
        /** CSV just imported: offer to delete it (it holds every password in plain text). */
        val importedCsv: Uri? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Password chosen in the dialog, kept only until the file picker returns. */
    private var pendingPassword: CharArray? = null
    private val policy = MasterPasswordPolicy()

    /** Call right before launching a system file picker. */
    fun beforePicker() = autoLock.holdForExternalActivity()

    /** Returns an error for the dialog, or null if the picker may open. */
    fun prepareBackupPassword(password: String, confirm: String): String? {
        val pw = password.toCharArray()
        val e = policy.evaluate(pw, confirm.toCharArray())
        if (!e.matches) return "Passwords don't match".also { SecureBytes.wipe(pw) }
        if (!e.longEnough) return "Use at least ${MasterPasswordPolicy.MIN_LENGTH} characters".also { SecureBytes.wipe(pw) }
        if (!e.strongEnough) return "Choose a stronger backup password".also { SecureBytes.wipe(pw) }
        replacePending(pw)
        return null
    }

    fun exportEncrypted(uri: Uri?) = withPending(uri) { target, pw ->
        val bytes = backups.exportEncrypted(pw)
        write(target, bytes)
        "Encrypted backup saved. Keep the backup password somewhere safe — without it the file can't be opened."
    }

    fun setImportPassword(password: String) = replacePending(password.toCharArray())

    fun importEncrypted(uri: Uri?) = withPending(uri) { source, pw ->
        val report = backups.importEncrypted(read(source), pw)
        "Backup restored: ${describe(report)}"
    }

    fun importCsv(uri: Uri?) {
        uri ?: return
        run {
            val text = read(uri).decodeToString()
            val report = backups.importLogins(PasswordCsv.import(text))
            _ui.update { it.copy(importedCsv = uri) }
            "Imported ${describe(report)}"
        }
    }

    /** Plaintext export (spec §6.4): master password re-entry first. Null = ok to open the picker. */
    suspend fun verifyForPlaintextExport(masterPassword: String): String? {
        val pw = masterPassword.toCharArray()
        val r = try {
            manager.verifyMasterPassword(pw)
        } finally {
            SecureBytes.wipe(pw)
        }
        return when (r) {
            UnlockResult.Success -> null
            UnlockResult.WrongPassword -> "Wrong master password"
            is UnlockResult.LockedOut -> "Too many attempts. Wait ${formatCountdown(r.remainingMillis)}"
            UnlockResult.NoVault -> "No vault"
        }
    }

    fun exportCsv(uri: Uri?) {
        uri ?: return
        run {
            write(uri, backups.exportCsv().toByteArray())
            "Unencrypted CSV saved. Anyone who gets this file can read every password — delete it once you've used it."
        }
    }

    fun deleteImportedCsv() {
        val uri = _ui.value.importedCsv ?: return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                try {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                } catch (e: Exception) {
                    AresLog.w("Backup", "Could not delete imported CSV", e)
                    false
                }
            }
            _ui.update {
                it.copy(
                    importedCsv = null,
                    message = if (deleted) "CSV file deleted" else "Couldn't delete it here — delete the CSV in your Files app",
                )
            }
        }
    }

    fun dismissCsvPrompt() = _ui.update { it.copy(importedCsv = null) }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    fun cancelPending() = replacePending(null)

    private fun withPending(uri: Uri?, action: suspend (Uri, CharArray) -> String) {
        val pw = pendingPassword
        if (uri == null || pw == null) {
            replacePending(null)
            return
        }
        run {
            try {
                action(uri, pw)
            } finally {
                replacePending(null)
            }
        }
    }

    private fun run(action: suspend () -> String) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val message = try {
                action()
            } catch (_: AuthenticationFailedException) {
                "Wrong backup password (or the file was modified)"
            } catch (e: InvalidBackupException) {
                e.message ?: "Not an AresVault backup"
            } catch (e: CsvFormatException) {
                "Unrecognised CSV: ${e.message}. Export from Google Password Manager or Firefox."
            } catch (e: IOException) {
                "Couldn't access the file: ${e.message}"
            }
            _ui.update { it.copy(busy = false, message = message) }
        }
    }

    private suspend fun read(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(MAX_FILE_BYTES + 1)
            if (bytes.size > MAX_FILE_BYTES) throw IOException("File is too large")
            bytes
        } ?: throw IOException("Can't open file")
    }

    private suspend fun write(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IOException("Can't write file")
    }

    private fun describe(r: ImportReport) = buildList {
        add("${r.added} added")
        if (r.updated > 0) add("${r.updated} updated")
        if (r.skipped > 0) add("${r.skipped} already present")
    }.joinToString(", ")

    private fun pendingPasswordCopy(): CharArray? = pendingPassword?.copyOf()
    private fun replacePendingPublic() = replacePending(null)

    private fun replacePending(pw: CharArray?) {
        pendingPassword?.let(SecureBytes::wipe)
        pendingPassword = pw
    }

    override fun onCleared() = replacePending(null)

    private companion object {
        const val MAX_FILE_BYTES = 50 * 1024 * 1024
    }
}

/** InputStream.readNBytes is API 33+; read up to [limit] bytes on any level. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
