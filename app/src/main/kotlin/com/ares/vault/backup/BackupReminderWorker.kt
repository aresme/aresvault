package com.ares.vault.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ares.vault.MainActivity
import com.ares.vault.R
import com.ares.vault.prefs.AppPrefs
import java.util.concurrent.TimeUnit

/**
 * Daily check that posts "Time to email your backup" once a week has passed since the last
 * emailed backup. The notification shows no vault contents; tapping it opens AresVault, which
 * still requires unlock before anything is exported.
 */
class BackupReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = AppPrefs(applicationContext)
        if (EmailBackup.isReminderDue(prefs.emailReminderEnabled, prefs.backupEmail, prefs.lastEmailBackupAt, System.currentTimeMillis())) {
            notify(applicationContext, prefs.backupEmail)
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "aresvault-email-backup-reminder"
        private const val CHANNEL = "backup_reminders"
        private const val NOTIFICATION_ID = 7001
        const val EXTRA_EMAIL_BACKUP = "com.ares.vault.EMAIL_BACKUP"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupReminderWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }

        fun dismiss(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

        fun notify(context: Context, address: String) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
            ) {
                return
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Backup reminders", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).putExtra(EXTRA_EMAIL_BACKUP, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Time to email your backup")
                .setContentText("Send an encrypted backup to $address")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
