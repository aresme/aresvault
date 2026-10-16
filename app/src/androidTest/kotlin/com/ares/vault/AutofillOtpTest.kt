package com.ares.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ares.vault.core.otp.TotpSpec
import com.ares.vault.data.apps.AppSignatures
import com.ares.vault.data.repo.CredentialDraft
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** A one-time-code field gets the current 2FA code of the matching login. */
@RunWith(AndroidJUnit4::class)
class AutofillOtpTest : AutofillTestBase("otp") {

    @Test fun otpFieldIsFilledWithCurrentCode() {
        val spec = TotpSpec.fromKey("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP", issuer = "Test", account = "otp-user")
        runBlocking {
            hooks.credentials().create(
                CredentialDraft(
                    title = "Test app", username = "otp-user", password = "pw",
                    appPackage = pkg, appCertSha256 = AppSignatures.currentSha256(context.packageManager, pkg),
                    totpUri = spec.toUri(),
                ),
            )
        }
        tapSuggestion("otp", "2FA code · Test app")
        Thread.sleep(1_200)
        val now = System.currentTimeMillis()
        val valid = setOf(spec.code(now), spec.code(now - 30_000)) // tolerate a window rollover
        scenario.onActivity { assertThat(it.password.text.toString()).isIn(valid) }
    }
}
