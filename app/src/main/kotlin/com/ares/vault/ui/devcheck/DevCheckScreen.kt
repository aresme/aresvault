package com.ares.vault.ui.devcheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ares.vault.ui.BiometricAuthenticator
import kotlinx.coroutines.launch

@Composable
fun DevCheckScreen(onOpenGenerator: () -> Unit, vm: DevCheckViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }

    // Hand the ViewModel a CharArray and clear the field immediately.
    fun takePassword(): CharArray = password.toCharArray().also { password = "" }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AresVault · Phase 1 crypto check", style = MaterialTheme.typography.titleLarge)
            Text(
                "Vault: ${if (!ui.vaultExists) "none" else if (ui.unlocked) "UNLOCKED" else "locked"}" +
                    " · fingerprint slot: ${if (ui.hasBiometric) "yes" else "no"}" +
                    (if (ui.unlocked) " · entries: ${ui.entryCount}" else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = onOpenGenerator) { Text("Password generator") }

            if (!ui.unlocked) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Master password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            when {
                !ui.vaultExists -> Button(onClick = { vm.create(takePassword()) }, enabled = !ui.busy) {
                    Text("Create vault (benchmarks Argon2id)")
                }
                !ui.unlocked -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.unlock(takePassword()) }, enabled = !ui.busy) { Text("Unlock") }
                    if (ui.hasBiometric) {
                        OutlinedButton(onClick = {
                            val cipher = vm.biometricUnlockCipher() ?: return@OutlinedButton
                            scope.launch {
                                when (val r = BiometricAuthenticator.authenticate(activity, cipher, "Unlock AresVault")) {
                                    is BiometricAuthenticator.Result.Success -> vm.unlockWithBiometric(r.cipher)
                                    is BiometricAuthenticator.Result.Error -> vm.log("Biometric error: ${r.message}")
                                    BiometricAuthenticator.Result.Cancelled -> vm.log("Biometric cancelled")
                                }
                            }
                        }) { Text("Fingerprint") }
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::addSample, enabled = !ui.busy) { Text("Add sample") }
                        Button(onClick = vm::lock) { Text("Lock") }
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val cipher = vm.enrollCipher()
                            when (val r = BiometricAuthenticator.authenticate(
                                activity, cipher, "Enable fingerprint unlock", negativeText = "Cancel",
                            )) {
                                is BiometricAuthenticator.Result.Success -> vm.completeEnrollment(r.cipher)
                                is BiometricAuthenticator.Result.Error -> vm.log("Biometric error: ${r.message}")
                                BiometricAuthenticator.Result.Cancelled -> vm.log("Biometric cancelled")
                            }
                        }
                    }) { Text(if (ui.hasBiometric) "Re-enrol fingerprint" else "Enable fingerprint unlock") }

                    HorizontalDivider()
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Current password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newPassword, onValueChange = { newPassword = it },
                        label = { Text("New password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = {
                        vm.changePassword(takePassword(), newPassword.toCharArray().also { newPassword = "" })
                    }, enabled = !ui.busy) { Text("Change master password") }
                }
            }

            if (ui.vaultExists) {
                OutlinedButton(onClick = vm::destroy, enabled = !ui.busy) { Text("Destroy vault (test only)") }
            }

            HorizontalDivider()
            ui.log.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
