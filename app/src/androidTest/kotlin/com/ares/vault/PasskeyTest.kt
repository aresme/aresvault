package com.ares.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.ares.vault.passkey.AresCredentialProviderService
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Passkey registration + sign-in through Android's real Credential Manager, verified by the
 * test app acting as relying party (ES256 signature, challenge, rpIdHash, UP/UV flags).
 * The vault is kept locked so user verification uses the master password (no finger needed).
 */
@RunWith(AndroidJUnit4::class)
class PasskeyTest : AutofillTestBase("passkey") {
    private var previousCred: String? = null
    private var previousPrimary: String? = null

    @Before fun enableProvider() {
        previousCred = shell("settings get secure credential_service").takeIf { it.isNotEmpty() && it != "null" }
        previousPrimary = shell("settings get secure credential_service_primary").takeIf { it.isNotEmpty() && it != "null" }
        val c = "$pkg/${AresCredentialProviderService::class.java.name}"
        shell("settings put secure credential_service $c")
        shell("settings put secure credential_service_primary $c")
    }

    @After fun restoreProvider() {
        shell(previousCred?.let { "settings put secure credential_service $it" } ?: "settings delete secure credential_service")
        shell(previousPrimary?.let { "settings put secure credential_service_primary $it" } ?: "settings delete secure credential_service_primary")
    }

    private fun tapButton(regex: String) = device.wait(Until.findObject(By.text(Pattern.compile(regex))), 10_000)!!.click()

    private fun status(expected: String) {
        val ok = device.wait(Until.hasObject(By.desc("passkey_status").text(expected)), 15_000)
        if (!ok) error("status was: " + device.findObject(By.desc("passkey_status"))?.text)
    }

    @Test fun createThenSignInWithPasskey() {
        hooks.vaultManager().lock()

        tapButton("(?i)create passkey")
        tapButton("Continue") // Credential Manager sheet → AresVault
        enterMasterPassword()
        tapButton("Allow") // one-time approval for this app + signature + rpId
        status("created")

        hooks.vaultManager().lock()
        tapButton("(?i)sign in with passkey")
        tapButton("Continue") // "Unlock AresVault"
        enterMasterPassword()
        tapButton("Continue") // pick the passkey; no second verification inside 30 s
        status("verified")
    }
}
