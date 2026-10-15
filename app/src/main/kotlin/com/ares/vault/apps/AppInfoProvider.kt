package com.ares.vault.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.ares.vault.data.apps.AppSignatures
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Labels/icons of installed apps, from the local PackageManager only (no network). */
@Singleton
class AppInfoProvider @Inject constructor(@ApplicationContext context: Context) {
    private val pm: PackageManager = context.packageManager
    private val labels = LruCache<String, String>(256)
    private val icons = LruCache<String, ImageBitmap>(64)

    data class InstalledApp(val packageName: String, val label: String)

    fun label(packageName: String): String? {
        labels.get(packageName)?.let { return it }
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                .also { labels.put(packageName, it) }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun icon(packageName: String): ImageBitmap? {
        icons.get(packageName)?.let { return it }
        return try {
            pm.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
                .also { icons.put(packageName, it) }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun certSha256(packageName: String): String? = AppSignatures.currentSha256(pm, packageName)

    /** Apps with a launcher icon, for the "linked app" picker. */
    fun launchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .mapNotNull { pkg -> label(pkg)?.let { InstalledApp(pkg, it) } }
            .sortedBy { it.label.lowercase() }
    }
}
