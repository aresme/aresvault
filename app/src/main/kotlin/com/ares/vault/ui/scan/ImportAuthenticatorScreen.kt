package com.ares.vault.ui.scan

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ares.vault.core.otp.GoogleAuthenticatorMigration
import com.ares.vault.core.otp.TotpSpec

/**
 * Google Authenticator → ⋮ → Transfer accounts → Export shows one or more QR codes. Scan them
 * all; when the last batch is in, [onDone] gets every account.
 */
@Composable
fun ImportAuthenticatorScreen(onBack: () -> Unit, onDone: (List<TotpSpec>, skippedHotp: Int) -> Unit) {
    val batches = remember { mutableMapOf<Int, GoogleAuthenticatorMigration.Batch>() }
    var hint by remember { mutableStateOf("In Google Authenticator: ⋮ → Transfer accounts → Export accounts, then scan the QR code(s) here.") }
    var finished by remember { mutableStateOf(false) }
    ScanScaffold("Import from Google Authenticator", onBack, hint) {
        QrScanner(Modifier.fillMaxSize()) { text ->
            if (finished) return@QrScanner
            val batch = try {
                GoogleAuthenticatorMigration.parse(text)
            } catch (e: IllegalArgumentException) {
                hint = e.message ?: "Not a Google Authenticator export code"
                return@QrScanner
            }
            batches[batch.batchIndex] = batch
            if (batches.size >= batch.batchSize) {
                finished = true
                onDone(batches.values.flatMap { it.accounts }, batches.values.sumOf { it.skippedHotp })
            } else {
                hint = "Scanned ${batches.size} of ${batch.batchSize} codes — swipe to the next QR code in Google Authenticator."
            }
        }
    }
}
