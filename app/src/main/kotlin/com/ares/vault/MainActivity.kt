package com.ares.vault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.ares.vault.ui.devcheck.DevCheckScreen
import com.ares.vault.ui.theme.AresVaultTheme
import dagger.hilt.android.AndroidEntryPoint

/** Single activity. FragmentActivity because BiometricPrompt requires it. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Spec §4.3: block screenshots/screen recording and blank the recents thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            AresVaultTheme {
                DevCheckScreen()
            }
        }
    }
}
