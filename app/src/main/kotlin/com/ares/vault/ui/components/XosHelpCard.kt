package com.ares.vault.ui.components

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Spec §6.5: XOS kills/freezes background apps aggressively. If autofill suggestions stop
 * appearing, AresVault needs to be exempt from battery optimisation and allowed to auto-start.
 */
@Composable
fun XosHelpCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(isExempt(context)) }
    LifecycleResumeEffect(Unit) {
        exempt = isExempt(context)
        onPauseOrDispose {}
    }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (exempt) Icons.Filled.CheckCircle else Icons.Filled.BatteryChargingFull,
                    null,
                    tint = if (exempt) Color(0xFF30C48D) else MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (exempt) "  Battery optimisation is off for AresVault" else "  Keep autofill working on Infinix",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                "If AresVault suggestions stop appearing, XOS has probably put it to sleep:\n" +
                    "1. Allow AresVault to run in the background (button below).\n" +
                    "2. Phone Master → App management (or Settings → Apps → Auto-start) → allow AresVault.\n" +
                    "3. In Recents, pull down on AresVault to lock it.\n" +
                    "4. App settings → turn off “Manage app if unused” so Android never hibernates it.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!exempt) {
                OutlinedButton(onClick = { requestExemption(context) }) { Text("Allow background running") }
            }
            TextButton(onClick = { open(context, appDetails(context)) }) { Text("Open AresVault app settings") }
        }
    }
}

private fun isExempt(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

/**
 * Direct exemption prompt. A password manager whose core feature is a system-bound
 * service is an allowed use of REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
 */
@SuppressLint("BatteryLife")
private fun requestExemption(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    if (!open(context, direct)) {
        if (!open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) open(context, appDetails(context))
    }
}

private fun appDetails(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

private fun open(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}
