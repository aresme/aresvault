package com.ares.vault.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ares.vault.ui.BiometricAuthenticator
import com.ares.vault.ui.components.PasswordField
import com.ares.vault.ui.components.formatCountdown
import kotlinx.coroutines.launch

@Composable
fun LockScreen(vm: LockViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()

    fun promptBiometric() {
        val cipher = vm.biometricCipher() ?: return
        scope.launch {
            when (val r = BiometricAuthenticator.authenticate(activity, cipher, "Unlock AresVault")) {
                is BiometricAuthenticator.Result.Success -> vm.unlockWithBiometric(r.cipher)
                is BiometricAuthenticator.Result.Error -> vm.biometricError(r.message)
                BiometricAuthenticator.Result.Cancelled -> Unit
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!vm.autoPromptDone && ui.biometricAvailable && !ui.lockedOut) {
            vm.autoPromptDone = true
            promptBiometric()
        }
    }

    LockContent(
        state = ui,
        onPasswordChange = vm::onPassword,
        onUnlock = vm::unlock,
        onBiometric = ::promptBiometric,
    )
}

/** Stateless lock UI (unit-tested with Robolectric). */
@Composable
fun LockContent(
    state: LockUiState,
    onPasswordChange: (String) -> Unit,
    onUnlock: () -> Unit,
    onBiometric: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text("AresVault is locked", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            PasswordField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = "Master password",
                isError = state.error != null && !state.lockedOut,
                onImeAction = onUnlock,
                modifier = Modifier.testTag("password"),
            )
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.lockedOut) {
                Text(
                    "Too many wrong attempts. Try again in ${formatCountdown(state.lockoutRemainingMillis)}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("lockout"),
                )
            } else {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error")) }
            }
            Button(
                onClick = onUnlock,
                enabled = !state.busy && !state.lockedOut && state.password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("unlock"),
            ) { Text("Unlock") }
            if (state.biometricAvailable) {
                OutlinedButton(
                    onClick = onBiometric,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Fingerprint, null)
                    Text("  Use fingerprint")
                }
            }
        }
    }
}
