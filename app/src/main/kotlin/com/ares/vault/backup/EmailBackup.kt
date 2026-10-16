package com.ares.vault.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate

/**
 * Hands an encrypted `.aresvault` backup to the user's email app (spec follow-up: "send to a
 * dedicated email"). AresVault itself never touches the network: the email app sends it, and the
 * attachment is useless without the backup password.
 */
object EmailBackup {
    private const val DIR = "email-backups"
    const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    fun isValidAddress(address: String): Boolean = Patterns.EMAIL_ADDRESS.matcher(address.trim()).matches()

    /** Weekly reminder is due when enabled, an address is set, and nothing was sent for a week. */
    fun isReminderDue(enabled: Boolean, address: String, lastSentAt: Long, now: Long): Boolean =
        enabled && address.isNotBlank() && now - lastSentAt >= WEEK_MS

    /** Writes the encrypted bytes to app cache (old copies removed) and returns a share intent. */
    fun shareIntent(context: Context, bytes: ByteArray, address: String, entryCount: Int): Intent {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val date = LocalDate.now().toString()
        val file = File(dir, "AresVault-$date.aresvault").apply { writeBytes(bytes) }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.backups", file)
        val body = """
            Encrypted AresVault backup from $date ($entryCount logins and passkeys).

            This file is encrypted with your backup password. Without it the file can't be opened.

            To restore: install AresVault, save this attachment to the phone, then open
            AresVault → Settings → Backup & import → Restore from backup.
        """.trimIndent()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
            putExtra(Intent.EXTRA_SUBJECT, "AresVault encrypted backup $date")
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Offer email apps only (not chat apps etc.). An Intent selector would do this, but XOS's
        // replacement chooser ignores it ("No apps can perform this action"), so target each
        // installed email app explicitly.
        val emailApps = context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0)
            .map { it.activityInfo.packageName }
            .distinct()
        val targeted = emailApps.map { Intent(send).setPackage(it) }
        return when (targeted.size) {
            0 -> Intent.createChooser(send, "Send encrypted backup")
            1 -> targeted.single()
            else -> Intent.createChooser(targeted.first(), "Send encrypted backup")
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, targeted.drop(1).toTypedArray())
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Removes cached attachments (called on app start). */
    fun cleanCache(context: Context) {
        File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
    }
}
