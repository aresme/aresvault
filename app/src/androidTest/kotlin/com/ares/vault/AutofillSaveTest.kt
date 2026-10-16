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
        // The prompt animates in: wait for the button itself, not just the title.
        val save = device.wait(Until.findObject(By.text(Pattern.compile("(?i)save")).clickable(true)), 8_000)
            ?: device.wait(Until.findObject(By.text(Pattern.compile("(?i)save"))), 4_000)
        assertThat(save).isNotNull()
        save.click()
    }

    private fun entries() = runBlocking { hooks.credentials().all() }

    /** Polls until the vault holds exactly one entry matching [password] (saving is async). */
    private fun awaitSaved(password: String) = run {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            entries().singleOrNull { it.password == password }?.let { return@run it }
            Thread.sleep(250)
        }
        error("Login with the expected password was not saved; entries=${entries().map { it.username }}")
    }

    @Test fun submittingNewLoginSavesIt() {
        typeAndSubmit("bob@test.dev", "Brand-new-pass-1")
        acceptSystemSavePrompt()
        val saved = awaitSaved("Brand-new-pass-1")
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
        val entry = awaitSaved("New-pass-456")
        val history = runBlocking { hooks.credentials().observeHistory(entry.id).first() }
        assertThat(history.map { it.password }).containsExactly("Old-pass-123")
    }

    @Test fun lockedVaultUnlocksBeforeSaving() {
        hooks.vaultManager().lock()
        typeAndSubmit("dave@test.dev", "Locked-save-789")
        acceptSystemSavePrompt()
        enterMasterPassword()
        val saved = awaitSaved("Locked-save-789")
        assertThat(saved.username).isEqualTo("dave@test.dev")
        assertThat(saved.password).isEqualTo("Locked-save-789")
    }
}
