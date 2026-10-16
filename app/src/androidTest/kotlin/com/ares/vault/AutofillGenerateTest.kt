package com.ares.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.ares.vault.core.generator.CharSets
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Sign-up form: "Generate strong password" fills password + confirmation. */
@RunWith(AndroidJUnit4::class)
class AutofillGenerateTest : AutofillTestBase("signup") {

    @Test fun generateFillsBothPasswordFields() {
        // Works with the vault locked: generating reads no stored secret.
        hooks.vaultManager().lock()
        tapSuggestion("password", "🔑 Generate strong password")
        val use = device.wait(Until.findObject(By.text("Use password")), 8_000)
        assertThat(use).isNotNull()
        use.click()
        Thread.sleep(1_000)
        scenario.onActivity {
            val pw = it.password.text.toString()
            assertThat(pw).hasLength(16) // default preset: Strong
            assertThat(it.confirm!!.text.toString()).isEqualTo(pw)
            assertThat(pw.any { c -> c in CharSets.SYMBOLS }).isTrue()
        }
    }

    @Test fun noSymbolsOptionRegeneratesWithoutSymbols() {
        tapSuggestion("password", "🔑 Generate strong password")
        assertThat(device.wait(Until.findObject(By.text("Use password")), 8_000)).isNotNull()
        device.findObject(By.textStartsWith("No symbols")).click()
        Thread.sleep(300)
        device.findObject(By.text("Use password")).click()
        Thread.sleep(1_000)
        scenario.onActivity {
            val pw = it.password.text.toString()
            assertThat(pw).hasLength(16)
            assertThat(pw.none { c -> c in CharSets.SYMBOLS }).isTrue()
        }
    }
}
