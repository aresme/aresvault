package com.ares.vault.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ares.vault.data.crypto.Argon2KtKdf
import com.ares.vault.data.crypto.VaultHeaderStore
import com.ares.vault.data.keystore.BiometricKeyStore
import com.ares.vault.data.session.UnlockResult
import com.ares.vault.data.session.VaultManager
import com.ares.vault.data.session.VaultSession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class VaultManagerTest {
    @get:Rule val foreground = ForegroundRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session = VaultSession()
    private val manager = VaultManager(
        context, Argon2KtKdf(), VaultHeaderStore(context), BiometricKeyStore(), session,
    )

    @After fun clean() = runTest { manager.destroy() }

    @Test fun createLockUnlockChangePassword() = runTest(timeout = 2.minutes) {
        manager.destroy()
        manager.create("first-master-pw".toCharArray())
        assertThat(session.isUnlocked).isTrue()

        manager.lock()
        assertThat(session.isUnlocked).isFalse()
        assertThat(manager.unlockWithPassword("nope-nope-nope".toCharArray()))
            .isEqualTo(UnlockResult.WrongPassword)
        assertThat(session.isUnlocked).isFalse()
        assertThat(manager.unlockWithPassword("first-master-pw".toCharArray()))
            .isEqualTo(UnlockResult.Success)
        assertThat(session.isUnlocked).isTrue()
        assertThat(session.databaseOrNull()!!.settings().get()).isNotNull()

        assertThat(manager.changePassword("first-master-pw".toCharArray(), "second-master-pw".toCharArray()))
            .isEqualTo(UnlockResult.Success)
        manager.lock()
        assertThat(manager.unlockWithPassword("first-master-pw".toCharArray()))
            .isEqualTo(UnlockResult.WrongPassword)
        // Same DEK, so the existing database (with its settings row) opens with the new password.
        assertThat(manager.unlockWithPassword("second-master-pw".toCharArray()))
            .isEqualTo(UnlockResult.Success)
        assertThat(session.databaseOrNull()!!.settings().get()).isNotNull()
    }
}
