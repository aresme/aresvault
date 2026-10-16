package com.ares.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** End-to-end "Save to AresVault?" and update flows. Phone must be unlocked. */
@RunWith(AndroidJUnit4::class)
class AutofillSaveTest : AutofillTestBase("login") {

    private fun typeAndSubmit(user: String, pass: String) {
        field("username").click()
        field("username").text = user
        field("password").click()
        field("password").text = pass
        device.pressBack() // hide keyboard / dropdown
        device.findObject(By.text(Pattern.compile("(?i)sign in"))).click()
    }

    /** Accepts the system save prompt ("Save … to AresVault?"). */
    private fun acceptSystemSavePrompt() {
        assertThat(device.wait(Until.findObject(By.textContains("AresVault")), 8_000)).isNotNull()
        device.findObject(By.text(Pattern.compile("(?i)save"))).click()
    }

    private fun entries() = runBlocking { hooks.credentials().all() }

    @Test fun submittingNewLoginSavesIt() {
        typeAndSubmit("bob@test.dev", "Brand-new-pass-1")
        acceptSystemSavePrompt()
        device.wait(Until.hasObject(By.pkg("com.android.launcher3")), 2_000)
        Thread.sleep(1_500)
        val saved = entries().single()
        assertThat(saved.username).isEqualTo("bob@test.dev")
        assertThat(saved.password).isEqualTo("Brand-new-pass-1")
        assertThat(saved.appPackage).isEqualTo(pkg)
        assertThat(saved.appCertSha256).isNotNull()
    }

    @Test fun changedPasswordAsksToUpdateAndKeepsHistory() {
        seed("carol@test.dev", "Old-pass-123")
        typeAndSubmit("carol@test.dev", "New-pass-456")
        acceptSystemSavePrompt()
        val update = device.wait(Until.findObject(By.text("Update")), 8_000)
        assertThat(update).isNotNull()
        update.click()
        Thread.sleep(1_500)
        val entry = entries().single()
        assertThat(entry.password).isEqualTo("New-pass-456")
        val history = runBlocking { hooks.credentials().observeHistory(entry.id).first() }
        assertThat(history.map { it.password }).containsExactly("Old-pass-123")
    }

    @Test fun lockedVaultUnlocksBeforeSaving() {
        hooks.vaultManager().lock()
        typeAndSubmit("dave@test.dev", "Locked-save-789")
        acceptSystemSavePrompt()
        enterMasterPassword()
        Thread.sleep(1_500)
        val saved = entries().single()
        assertThat(saved.username).isEqualTo("dave@test.dev")
        assertThat(saved.password).isEqualTo("Locked-save-789")
    }
}
