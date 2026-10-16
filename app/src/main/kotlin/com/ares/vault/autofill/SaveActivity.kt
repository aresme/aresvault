package com.ares.vault.autofill

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ares.vault.ui.theme.AresVaultTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Finishes a "Save to AresVault?" the service couldn't complete alone (spec §6.3): unlocks a
 * locked vault, or asks whether to update an existing login's password. The captured login
 * is fetched from [PendingSaveStore] by token; it never touches disk until saved encrypted.
 */
@AndroidEntryPoint
class SaveActivity : FragmentActivity() {
    private val vm: SaveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureAutofillWindow()
        vm.start(intent.getStringExtra(EXTRA_TOKEN))
        setContent {
            AresVaultTheme {
                val step by vm.step.collectAsStateWithLifecycle()
                val unlock by vm.unlock.state.collectAsStateWithLifecycle()
                when (val s = step) {
                    SaveViewModel.Step.Starting -> Unit
                    SaveViewModel.Step.Unlocking -> when (val u = unlock.step) {
                        is UnlockController.Step.Biometric -> BiometricUnlockEffect(vm.unlock, u, this, "Save login for ${vm.target}")
                        UnlockController.Step.Password -> AutofillSheet(vm::cancel) {
                            PasswordUnlockContent(unlock, vm.unlock, "to save your login for ${vm.target}")
                        }
                        UnlockController.Step.Idle -> Unit
                    }
                    is SaveViewModel.Step.AskUpdate -> AutofillSheet(vm::cancel) {
                        Text("Update password?", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "AresVault already has “${s.existing.title}” for " +
                                (s.existing.username.ifEmpty { "this account" }) +
                                ". The old password will be kept in its history.",
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                            TextButton(onClick = vm::cancel) { Text("Not now") }
                            TextButton(onClick = vm::saveAsNew) { Text("Save as new") }
                            Button(onClick = { vm.update(s.existing) }) { Text("Update") }
                        }
                    }
                    is SaveViewModel.Step.Done -> LaunchedEffect(s) {
                        s.message?.let { Toast.makeText(this@SaveActivity, it, Toast.LENGTH_SHORT).show() }
                        finish()
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_TOKEN = "com.ares.vault.autofill.SAVE_TOKEN"
    }
}
