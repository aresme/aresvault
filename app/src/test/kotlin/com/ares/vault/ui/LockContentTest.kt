package com.ares.vault.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ares.vault.ui.lock.LockContent
import com.ares.vault.ui.lock.LockUiState
import com.ares.vault.ui.theme.AresVaultTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockContentTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: LockUiState, onUnlock: () -> Unit = {}) = compose.setContent {
        AresVaultTheme { LockContent(state, onPasswordChange = {}, onUnlock = onUnlock, onBiometric = {}) }
    }

    @Test fun unlockDisabledWithoutPassword() {
        show(LockUiState())
        compose.onNodeWithTag("unlock").assertIsNotEnabled()
    }

    @Test fun unlockEnabledAndClickable() {
        var clicked = false
        show(LockUiState(password = "secret-pass")) { clicked = true }
        compose.onNodeWithTag("unlock").assertIsEnabled().performClick()
        assertThat(clicked).isTrue()
    }

    @Test fun lockoutShowsCountdownAndDisablesUnlock() {
        show(LockUiState(password = "x", lockoutRemainingMillis = 65_000))
        compose.onNodeWithTag("lockout").assertIsDisplayed()
        compose.onNodeWithText("Too many wrong attempts. Try again in 1:05").assertIsDisplayed()
        compose.onNodeWithTag("unlock").assertIsNotEnabled()
    }

    @Test fun fingerprintButtonOnlyWhenAvailable() {
        show(LockUiState(biometricAvailable = true))
        compose.onNodeWithText("Use fingerprint", substring = true).assertIsDisplayed()
    }
}
