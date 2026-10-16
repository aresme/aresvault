package com.ares.vault.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ares.vault.core.otp.TotpSpec

/**
 * Scans one `otpauth://totp/...` QR code (the code a website shows when you turn on 2FA).
 * [onResult] receives the validated URI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanTotpScreen(onBack: () -> Unit, onResult: (String) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    ScanScaffold("Scan 2FA QR code", onBack, error ?: "Point the camera at the QR code the website or app shows when you turn on two-factor authentication.") {
        QrScanner(Modifier.fillMaxSize()) { text ->
            if (done) return@QrScanner
            try {
                TotpSpec.parseUri(text)
                done = true
                onResult(text.trim())
            } catch (e: IllegalArgumentException) {
                error = e.message ?: "Not a 2FA QR code"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScaffold(title: String, onBack: () -> Unit, hint: String, camera: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            camera()
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { ScanHint(hint) }
            }
        }
    }
}
