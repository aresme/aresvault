package com.ares.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end login fill through the real Android autofill framework. Phone must be unlocked. */
@RunWith(AndroidJUnit4::class)
class AutofillLoginTest : AutofillTestBase("login") {

    @Test fun unlockedVaultFillsLoginFromDropdown() {
        seed(USER, PASS)
        tapSuggestion("username", USER)
        device.waitForIdle()
        Thread.sleep(800)
        scenario.onActivity {
            assertThat(it.username.text.toString()).isEqualTo(USER)
            assertThat(it.password.text.toString()).isEqualTo(PASS)
        }
    }

    @Test fun lockedVaultAsksForMasterPasswordBeforeFilling() {
        seed(USER, PASS)
        Thread.sleep(1_000) // let the locked-state index be written
        hooks.vaultManager().lock()
        tapSuggestion("username", USER) // label comes from the encrypted locked-state index
        Thread.sleep(500)
        scenario.onActivity { assertThat(it.password.text.toString()).isEmpty() } // nothing revealed yet
        enterMasterPassword() // no fingerprint enrolled in staging
        Thread.sleep(800)
        scenario.onActivity {
            assertThat(it.username.text.toString()).isEqualTo(USER)
            assertThat(it.password.text.toString()).isEqualTo(PASS)
        }
    }

    private companion object {
        const val USER = "alice@test.dev"
        const val PASS = "S3cret-pass!"
    }
}
