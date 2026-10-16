package com.ares.vault.ui.totp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.vault.core.otp.TotpSpec
import kotlinx.coroutines.delay

/** Current time, ticking every second while shown. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    return now
}

fun parseOrNull(uri: String?): TotpSpec? = uri?.let { runCatching { TotpSpec.parseUri(it) }.getOrNull() }

/** "123 456" grouping for readability. */
fun formatCode(code: String): String = if (code.length == 6) code.chunked(3).joinToString(" ") else code.chunked(4).joinToString(" ")

/** Live 2FA code with a countdown ring and copy button. */
@Composable
fun TotpCode(spec: TotpSpec, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    val now = rememberNow()
    val code = spec.code(now)
    val remaining = spec.secondsRemaining(now)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("2FA code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatCode(code), fontFamily = FontFamily.Monospace, fontSize = 28.sp, color = if (remaining <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            val label = listOf(spec.issuer, spec.account).filter { it.isNotEmpty() }.joinToString(" · ")
            if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { remaining / spec.periodSeconds.toFloat() }, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
            Text("$remaining", style = MaterialTheme.typography.labelSmall)
        }
        IconButton(onClick = { onCopy(code) }) { Icon(Icons.Filled.ContentCopy, "Copy 2FA code") }
    }
}
