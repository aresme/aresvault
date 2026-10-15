package com.ares.vault.ui.components

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.ares.vault.autofill.AutofillSetup

/** Autofill status checklist + "set as autofill service" button. Re-checks on resume. */
@Composable
fun AutofillSetupCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AutofillSetup.isEnabled(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        enabled = AutofillSetup.isEnabled(context)
        onPauseOrDispose {}
    }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (enabled) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    null,
                    tint = if (enabled) Color(0xFF30C48D) else MaterialTheme.colorScheme.error,
                )
                Text(
                    if (enabled) "  AresVault is your autofill service" else "  AresVault is not your autofill service",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (!enabled) {
                Text(
                    "Android lets one password manager fill passwords at a time. Choose AresVault in the next screen " +
                        "(on Infinix XOS it may be preset to Google).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = {
                    try {
                        context.startActivity(AutofillSetup.requestIntent(context))
                    } catch (_: ActivityNotFoundException) {
                        error = "Open Settings → Passwords & accounts → Autofill service and pick AresVault."
                    }
                }) { Text("Set AresVault as autofill service") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Text(AutofillSetup.CHROME_STEPS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
