package com.ares.vault.autofill

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.autofill.AutofillManager
import android.view.inputmethod.InlineSuggestionsRequest
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ares.vault.ui.BiometricAuthenticator
import com.ares.vault.ui.theme.AresVaultTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Launched by the autofill framework when the user picks an AresVault suggestion that needs
 * authentication. The AssistStructure comes from the *system* (EXTRA_ASSIST_STRUCTURE), so the
 * requester's identity is re-derived here rather than trusted from our own extras.
 */
@AndroidEntryPoint
class AutofillAuthActivity : FragmentActivity() {
    @Inject lateinit var contexts: FillContextFactory
    private val vm: AutofillAuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureAutofillWindow()

        val structure = IntentCompat.getParcelableExtra(intent, AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        val inline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            IntentCompat.getParcelableExtra(intent, AutofillManager.EXTRA_INLINE_SUGGESTIONS_REQUEST, InlineSuggestionsRequest::class.java)
        } else {
            null
        }
        val ctx = structure?.let { contexts.create(it, inline) }
        val mode = intent.getStringExtra(EXTRA_MODE)
        if (ctx == null || mode == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        vm.start(ctx, mode, intent.getStringExtra(EXTRA_CREDENTIAL_ID), BiometricAuthenticator.canConfirm(this))

        setContent {
            AresVaultTheme {
                val step by vm.step.collectAsStateWithLifecycle()
                val unlock by vm.unlock.state.collectAsStateWithLifecycle()
                AuthContent(step, unlock)
            }
        }
    }

    @Composable
    private fun AuthContent(step: AutofillAuthViewModel.Step, unlock: UnlockController.State) {
        when (step) {
            AutofillAuthViewModel.Step.Starting -> Unit
            AutofillAuthViewModel.Step.Unlocking -> when (val u = unlock.step) {
                is UnlockController.Step.Biometric -> BiometricUnlockEffect(vm.unlock, u, this, "Fill ${vm.target}")
                UnlockController.Step.Password -> AutofillSheet(vm::cancel) {
                    PasswordUnlockContent(unlock, vm.unlock, "to fill ${vm.target}")
                }
                UnlockController.Step.Idle -> Unit
            }
            AutofillAuthViewModel.Step.Confirm -> LaunchedEffect(step) {
                when (val r = BiometricAuthenticator.confirm(this@AutofillAuthActivity, "Fill with AresVault", vm.target)) {
                    BiometricAuthenticator.Result.Confirmed -> vm.onConfirmed()
                    is BiometricAuthenticator.Result.Error -> vm.verifyWithPassword(r.message)
                    else -> vm.cancel()
                }
            }
            is AutofillAuthViewModel.Step.Pick -> AutofillSheet(vm::cancel) { PickStep(step) }
            is AutofillAuthViewModel.Step.Done -> LaunchedEffect(step) { finishWith(step) }
        }
    }

    @Composable
    private fun ColumnScope.PickStep(step: AutofillAuthViewModel.Step.Pick) {
        var query by remember { mutableStateOf("") }
        var link by remember { mutableStateOf(true) }
        Text("Choose a login for ${vm.target}", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = link, onCheckedChange = { link = it })
            Text(vm.linkLabel())
        }
        val items = step.items.filter {
            query.isBlank() || it.title.contains(query, true) || it.username.contains(query, true) || it.sites().contains(query, true)
        }
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            items(items, key = { it.id }) { item ->
                Column(Modifier.fillMaxWidth().clickable { vm.pick(item, link) }.padding(vertical = 10.dp)) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOf(item.username, item.sites()).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TextButton(onClick = vm::cancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
    }

    private fun finishWith(step: AutofillAuthViewModel.Step.Done) {
        step.toast?.let { android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_SHORT).show() }
        if (step.result == null) {
            setResult(RESULT_CANCELED)
        } else {
            setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, step.result))
        }
        finish()
    }

    companion object {
        const val EXTRA_MODE = "com.ares.vault.autofill.MODE"
        const val EXTRA_CREDENTIAL_ID = "com.ares.vault.autofill.CREDENTIAL_ID"
        const val MODE_FILL = "fill"
        const val MODE_UNLOCK = "unlock"
        const val MODE_PICK = "pick"
        const val MODE_OTP = "otp"
    }
}
