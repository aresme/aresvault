package com.ares.vault

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ares.vault.autofill.AresAutofillService
import com.ares.vault.data.apps.AppSignatures
import com.ares.vault.data.repo.CredentialDraft
import com.ares.vault.debug.AutofillTestActivity
import com.ares.vault.debug.TestHooks
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end: the real Android autofill framework talks to AresAutofillService.
 * Runs against the "staging" package, so the user's vault is never touched.
 * Phone must be unlocked (screen on, no keyguard).
 */
@RunWith(AndroidJUnit4::class)
class AutofillLoginTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val hooks = EntryPointAccessors.fromApplication(context, TestHooks::class.java)
    private val pkg = context.packageName
    private var previousService: String? = null
    private lateinit var scenario: ActivityScenario<AutofillTestActivity>

    private fun shell(cmd: String): String = device.executeShellCommand(cmd).trim()

    @Before fun setUp() {
        previousService = shell("settings get secure autofill_service").takeIf { it.isNotEmpty() && it != "null" }
        shell("settings put secure autofill_service $pkg/${AresAutofillService::class.java.name}")
        // Gboard would show inline chips instead of the dropdown UiAutomator can see.
        context.getSharedPreferences(AresAutofillService.DEBUG_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(AresAutofillService.DEBUG_DISABLE_INLINE, true).commit()
        device.wakeUp()
        shell("wm dismiss-keyguard")
        // Open the form first: XOS freezes processes without a visible window.
        scenario = ActivityScenario.launch(AutofillTestActivity::class.java)
        runBlocking {
            hooks.vaultManager().destroy()
            hooks.vaultManager().create(MASTER.toCharArray())
            hooks.settings().update { it.copy(requireAuthToFill = false) }
            hooks.credentials().create(
                CredentialDraft(
                    title = "Test app", username = USER, password = PASS,
                    appPackage = pkg, appCertSha256 = AppSignatures.currentSha256(context.packageManager, pkg),
                ),
            )
        }
    }

    @After fun tearDown() {
        scenario.close()
        runBlocking { hooks.vaultManager().destroy() }
        if (previousService != null) {
            shell("settings put secure autofill_service $previousService")
        } else {
            shell("settings delete secure autofill_service")
        }
    }

    private fun focusUsernameAndPick(label: String) {
        device.wait(Until.findObject(By.desc("username")), 5_000)!!.click()
        val suggestion = device.wait(Until.findObject(By.text(label)), 8_000)
        assertThat(suggestion).isNotNull()
        suggestion.click()
    }

    @Test fun unlockedVaultFillsLoginFromDropdown() {
        focusUsernameAndPick(USER)
        device.waitForIdle()
        Thread.sleep(800)
        scenario.onActivity {
            assertThat(it.username.text.toString()).isEqualTo(USER)
            assertThat(it.password.text.toString()).isEqualTo(PASS)
        }
    }

    @Test fun lockedVaultAsksForMasterPasswordBeforeFilling() {
        Thread.sleep(1_000) // let the locked-state index be written
        hooks.vaultManager().lock()
        focusUsernameAndPick(USER) // label comes from the encrypted locked-state index
        // No fingerprint enrolled in staging → master password sheet.
        val field = device.wait(Until.findObject(By.text("Master password")), 8_000)
        assertThat(field).isNotNull()
        scenario.onActivity { assertThat(it.password.text.toString()).isEmpty() } // nothing revealed yet
        device.findObject(By.clazz("android.widget.EditText")).text = MASTER
        device.findObject(By.text("Unlock")).click()
        device.wait(Until.gone(By.text("Master password")), 15_000)
        Thread.sleep(800)
        scenario.onActivity {
            assertThat(it.username.text.toString()).isEqualTo(USER)
            assertThat(it.password.text.toString()).isEqualTo(PASS)
        }
    }

    private companion object {
        const val MASTER = "Test-master-pw-4821!"
        const val USER = "alice@test.dev"
        const val PASS = "S3cret-pass!"
    }
}
