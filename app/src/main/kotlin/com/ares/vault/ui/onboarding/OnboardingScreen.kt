package com.ares.vault.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ares.vault.core.policy.MasterPasswordPolicy
import com.ares.vault.ui.BiometricEnroller
import com.ares.vault.ui.components.AutofillSetupCard
import com.ares.vault.ui.components.PasswordField
import com.ares.vault.ui.components.XosHelpCard
import com.ares.vault.ui.components.ScoreBar
import com.ares.vault.ui.components.scoreLabel
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(vm: OnboardingViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (ui.step) {
                OnboardingViewModel.Step.WELCOME -> Welcome(onStart = vm::start)
                OnboardingViewModel.Step.PASSWORD -> CreatePassword(ui, vm)
                OnboardingViewModel.Step.CREATING -> Creating()
                OnboardingViewModel.Step.FINGERPRINT -> Fingerprint(ui, vm)
                OnboardingViewModel.Step.AUTOFILL -> Autofill(vm)
            }
        }
    }
}

@Composable
private fun Welcome(onStart: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
    Text("AresVault", style = MaterialTheme.typography.headlineLarge)
    Text(
        "A password vault that lives only on this phone. No account, no cloud, no internet access.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Bullet("Everything is encrypted with a key only your master password or fingerprint can unlock.")
    Bullet("If you forget the master password, the vault cannot be recovered. Export backups regularly.")
    Spacer(Modifier.height(16.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
}

@Composable
private fun CreatePassword(ui: OnboardingViewModel.UiState, vm: OnboardingViewModel) {
    Text("Create your master password", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Use at least ${MasterPasswordPolicy.MIN_LENGTH} characters. A few random words is easy to " +
            "remember and hard to guess.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PasswordField(
        value = ui.password,
        onValueChange = vm::onPassword,
        label = "Master password",
        imeAction = ImeAction.Next,
    )
    val e = ui.evaluation
    if (ui.password.isNotEmpty() && e != null) {
        ScoreBar(e.score, scoreLabel(e.score) + (e.warning?.let { " — $it" } ?: ""))
    }
    PasswordField(
        value = ui.confirm,
        onValueChange = vm::onConfirm,
        label = "Confirm master password",
        isError = ui.confirm.isNotEmpty() && e?.matches == false,
        supportingText = if (ui.confirm.isNotEmpty() && e?.matches == false) "Passwords don't match" else null,
        onImeAction = vm::create,
    )
    Check(e?.longEnough == true, "At least ${MasterPasswordPolicy.MIN_LENGTH} characters")
    Check(e?.strongEnough == true, "Strength “Strong” or better")
    Check(e?.matches == true && ui.confirm.isNotEmpty(), "Both entries match")
    e?.suggestions?.firstOrNull()?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = vm::create,
        enabled = e?.acceptable == true && ui.confirm.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Create vault") }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Creating() {
    Spacer(Modifier.height(96.dp))
    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
    Text(
        "Creating your vault…\nTuning encryption strength for this phone.",
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Fingerprint(ui: OnboardingViewModel.UiState, vm: OnboardingViewModel) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val scope = rememberCoroutineScope()
    val availability = vm.biometricAvailability(context)

    Spacer(Modifier.height(32.dp))
    Icon(Icons.Filled.Fingerprint, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
    Text("Unlock with your fingerprint?", style = MaterialTheme.typography.headlineSmall)
    ui.createdInfo?.let {
        Text("Vault created. $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        "Your fingerprint unlocks a hardware-protected key on this phone. If a new fingerprint is " +
            "ever added, AresVault will ask for your master password once and then let you re-enable it.",
    )
    when (availability) {
        BiometricEnroller.Availability.AVAILABLE -> Button(
            onClick = { scope.launch { vm.enableFingerprint(activity) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Enable fingerprint unlock") }
        BiometricEnroller.Availability.NONE_ENROLLED ->
            Text("No fingerprint is set up on this phone. Add one in system settings, then enable it in AresVault settings.", color = MaterialTheme.colorScheme.error)
        BiometricEnroller.Availability.UNAVAILABLE ->
            Text("Strong biometric unlock isn't available on this device.", color = MaterialTheme.colorScheme.error)
    }
    ui.fingerprintMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    TextButton(onClick = vm::toAutofill, modifier = Modifier.fillMaxWidth()) { Text("Skip for now") }
}

@Composable
private fun Autofill(vm: OnboardingViewModel) {
    Spacer(Modifier.height(32.dp))
    Icon(Icons.Filled.Password, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
    Text("Fill passwords everywhere", style = MaterialTheme.typography.headlineSmall)
    Text(
        "AresVault can suggest your logins and strong new passwords in apps and browsers, " +
            "like Google Password Manager — but everything stays on this phone.",
    )
    AutofillSetupCard()
    XosHelpCard()
    Button(onClick = vm::finish, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun Bullet(text: String) = Text("•  $text", style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Check(ok: Boolean, label: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            null,
            tint = if (ok) Color(0xFF30C48D) else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
        Text("  $label", style = MaterialTheme.typography.bodyMedium)
    }
}
