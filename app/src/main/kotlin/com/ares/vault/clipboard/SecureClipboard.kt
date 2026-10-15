package com.ares.vault.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ares.vault.data.AresLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies secrets to the clipboard marked sensitive (hidden from the Android 13+ clipboard
 * preview and keyboard suggestions) and clears them after a timeout (spec §4.3).
 *
 * Clearing is scheduled twice: an in-process timer (precise) and a WorkManager job that
 * survives the process being killed or frozen by XOS.
 */
@Singleton
class SecureClipboard @Inject constructor(@ApplicationContext private val context: Context) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timer: Job? = null

    fun copySecret(label: String, secret: CharArray, clearAfterSeconds: Int = DEFAULT_CLEAR_SECONDS) {
        // ClipData requires a CharSequence; this String copy is unavoidable.
        val clip = ClipData.newPlainText(LABEL_PREFIX + label, String(secret))
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            } else {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        scheduleClear(clearAfterSeconds)
    }

    fun copyPlain(label: String, text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText(LABEL_PREFIX + label, text))
    }

    /** Clears now if the clipboard still holds something we put there. */
    fun clearIfOurs() {
        timer?.cancel()
        clear(context)
    }

    private fun scheduleClear(seconds: Int) {
        timer?.cancel()
        timer = scope.launch {
            delay(seconds * 1000L)
            clear(context)
        }
        val request = OneTimeWorkRequestBuilder<ClipboardClearWorker>()
            .setInitialDelay(seconds.toLong() + 2, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val DEFAULT_CLEAR_SECONDS = 30
        private const val LABEL_PREFIX = "AresVault: "
        private const val WORK_NAME = "aresvault-clipboard-clear"

        internal fun clear(context: Context) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            // In the background Android 10+ hides the clip description, so we can only verify
            // ownership when visible; otherwise clear unconditionally (secret safety first).
            val label = clipboard.primaryClipDescription?.label?.toString()
            if (label != null && !label.startsWith(LABEL_PREFIX)) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            AresLog.d("Clipboard", "Clipboard cleared")
        }
    }
}

class ClipboardClearWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        SecureClipboard.clear(applicationContext)
        return Result.success()
    }
}
