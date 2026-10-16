package com.ares.vault.ui.scan

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ares.vault.core.otp.TotpSpec
import com.ares.vault.data.backup.TotpImporter
import com.ares.vault.ui.PendingScan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScanResultViewModel @Inject constructor(private val pending: PendingScan) : ViewModel() {
    fun deliver(uri: String) = pending.put(uri)
}

@HiltViewModel
class TotpImportViewModel @Inject constructor(private val importer: TotpImporter) : ViewModel() {
    val result = MutableStateFlow<String?>(null)

    fun import(specs: List<TotpSpec>, skippedHotp: Int) {
        viewModelScope.launch {
            val r = importer.import(specs)
            result.value = buildList {
                add("${r.updated} added to existing logins")
                add("${r.added} saved as new 2FA entries")
                if (r.skipped > 0) add("${r.skipped} already in AresVault")
                if (skippedHotp > 0) add("$skippedHotp counter-based codes skipped (not supported)")
            }.joinToString("\n")
        }
    }
}

@Composable
fun ImportAuthenticatorRoute(onBack: () -> Unit, vm: TotpImportViewModel = hiltViewModel()) {
    val result by vm.result.collectAsStateWithLifecycle()
    ImportAuthenticatorScreen(onBack = onBack, onDone = vm::import)
    result?.let {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text("2FA codes imported") },
            text = {
                Column {
                    Text(it)
                    Text("\nYou can now remove these accounts from Google Authenticator if you want AresVault to be your only authenticator.")
                }
            },
            confirmButton = { TextButton(onClick = onBack) { Text("Done") } },
        )
    }
}
