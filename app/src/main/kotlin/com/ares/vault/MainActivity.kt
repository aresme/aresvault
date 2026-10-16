package com.ares.vault

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.ares.vault.ui.AresApp
import com.ares.vault.ui.theme.AresVaultTheme
import dagger.hilt.android.AndroidEntryPoint

/** Single activity. FragmentActivity because BiometricPrompt requires it. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Spec §4.3: block screenshots/screen recording and blank the recents thumbnail.
        // Compose dialogs and bottom sheets inherit this flag (SecureFlagPolicy.Inherit).
        if (!BuildConfig.ALLOW_SCREENSHOTS) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Keep the vault's own fields invisible to *any* autofill service (including another
        // password manager such as Google's), so it can't read or offer to save our secrets.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        enableEdgeToEdge()
        setContent {
            AresVaultTheme {
                AresApp()
            }
        }
    }
}
