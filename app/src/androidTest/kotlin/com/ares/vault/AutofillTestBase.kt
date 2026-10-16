package com.ares.vault

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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

/**
 * Shared setup for end-to-end autofill tests: makes the staging app the autofill service,
 * creates a throwaway vault and restores the user's autofill service afterwards.
 * Runs against com.ares.vault.staging; the real AresVault install is never touched.
 */
abstract class AutofillTestBase(private val mode: String) {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val device: UiDevice = UiDevice.getInstance(instrumentation)
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected val hooks: TestHooks = EntryPointAccessors.fromApplication(context, TestHooks::class.java)
    protected val pkg: String = context.packageName
    protected lateinit var scenario: ActivityScenario<AutofillTestActivity>
    private var previousService: String? = null

    protected fun shell(cmd: String): String = device.executeShellCommand(cmd).trim()

    @Before fun setUpAutofill() {
        previousService = shell("settings get secure autofill_service").takeIf { it.isNotEmpty() && it != "null" }
        shell("settings put secure autofill_service $pkg/${AresAutofillService::class.java.name}")
        device.wakeUp()
        shell("wm dismiss-keyguard")
        // Open the form first: XOS freezes processes without a visible window.
        scenario = ActivityScenario.launch(
            Intent(context, AutofillTestActivity::class.java).putExtra("mode", mode),
        )
        runBlocking {
            hooks.vaultManager().destroy()
            hooks.vaultManager().create(MASTER.toCharArray())
            hooks.settings().update { it.copy(requireAuthToFill = false) }
        }
    }

    @After fun tearDownAutofill() {
        scenario.close()
        runBlocking { hooks.vaultManager().destroy() }
        if (previousService != null) {
            shell("settings put secure autofill_service $previousService")
        } else {
            shell("settings delete secure autofill_service")
        }
    }

    protected fun seed(username: String, password: String) = runBlocking {
        hooks.credentials().create(
            CredentialDraft(
                title = "Test app", username = username, password = password,
                appPackage = pkg, appCertSha256 = AppSignatures.currentSha256(context.packageManager, pkg),
            ),
        )
    }

    protected fun field(desc: String) = device.wait(Until.findObject(By.desc(desc)), 5_000)!!

    protected fun tapSuggestion(fieldDesc: String, label: String) {
        field(fieldDesc).click()
        val suggestion = device.wait(Until.findObject(By.text(label)), 8_000)
        assertThat(suggestion).isNotNull()
        suggestion.click()
    }

    protected fun enterMasterPassword() {
        assertThat(device.wait(Until.findObject(By.text("Master password")), 8_000)).isNotNull()
        device.findObject(By.clazz("android.widget.EditText")).text = MASTER
        device.findObject(By.text("Unlock")).click()
        device.wait(Until.gone(By.text("Master password")), 15_000)
    }

    protected companion object {
        const val MASTER = "Test-master-pw-4821!"
    }
}
