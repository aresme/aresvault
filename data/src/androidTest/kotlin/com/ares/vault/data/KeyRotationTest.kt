package com.ares.vault.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ares.vault.core.crypto.VaultKeyHierarchy
import com.ares.vault.core.policy.BruteForceGuard
import com.ares.vault.data.autofill.AutofillIndexStore
import com.ares.vault.data.backup.BackupRepository
import com.ares.vault.data.crypto.Argon2KtKdf
import com.ares.vault.data.crypto.VaultHeaderStore
import com.ares.vault.data.db.VaultDatabase
import com.ares.vault.data.keystore.BiometricKeyStore
import com.ares.vault.data.repo.CredentialDraft
import com.ares.vault.data.repo.CredentialRepository
import com.ares.vault.data.session.AndroidVaultClock
import com.ares.vault.data.session.PrefsLockoutStore
import com.ares.vault.data.session.UnlockResult
import com.ares.vault.data.session.VaultManager
import com.ares.vault.data.session.VaultSession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class KeyRotationTest {
    @get:Rule val foreground = ForegroundRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session = VaultSession()
    private val headers = VaultHeaderStore(context)
    private val kdf = Argon2KtKdf()
    private val manager = VaultManager(
        context, kdf, headers, BiometricKeyStore(), session,
        BruteForceGuard(PrefsLockoutStore(context), AndroidVaultClock(context)), AutofillIndexStore(context),
    )
    private val repo = CredentialRepository(session)
    private val pw = "rotation-master-pw-1".toCharArray()

    @After fun clean() = runTest { manager.destroy() }

    @Test fun rotationReKeysDatabaseAndOldKeyStopsWorking() = runTest(timeout = 3.minutes) {
        manager.destroy()
        manager.create(pw.copyOf())
        repo.create(CredentialDraft(title = "Kept", username = "u", password = "p"))
        val oldHeader = headers.read()!!
        val oldDek = VaultKeyHierarchy(kdf).unlock(oldHeader, pw.copyOf())

        assertThat(manager.rotateDatabaseKey(pw.copyOf())).isEqualTo(UnlockResult.Success)
        assertThat(repo.all().single().title).isEqualTo("Kept") // still usable after re-key
        manager.lock()

        // An old header copy + the right password no longer opens the database.
        assertThrows(Exception::class.java) { VaultDatabase.open(context, oldDek).first.close() }
        assertThat(manager.unlockWithPassword(pw.copyOf())).isEqualTo(UnlockResult.Success)
        assertThat(repo.all().single().title).isEqualTo("Kept")
    }

    @Test fun interruptedRotationIsRecoveredOnUnlock() = runTest(timeout = 3.minutes) {
        manager.destroy()
        manager.create(pw.copyOf())
        repo.create(CredentialDraft(title = "Survivor", username = "u", password = "p"))
        val beforeRotation = headers.read()!!
        manager.rotateDatabaseKey(pw.copyOf())
        val afterRotation = headers.read()!!
        manager.lock()
        // Simulate a crash after the DB was re-keyed but before the header was promoted.
        headers.write(beforeRotation)
        headers.writePending(afterRotation)

        assertThat(manager.unlockWithPassword(pw.copyOf())).isEqualTo(UnlockResult.Success)
        assertThat(repo.all().single().title).isEqualTo("Survivor")
        assertThat(headers.readPending()).isNull()
        assertThat(headers.read()).isEqualTo(afterRotation)
    }

    @Test fun backupRoundTripRestoresEverything() = runTest(timeout = 3.minutes) {
        manager.destroy()
        manager.create(pw.copyOf())
        val backups = BackupRepository(session, repo, kdf)
        val id = repo.create(CredentialDraft(title = "GitHub", username = "alice", password = "v1", webDomain = "github.com"))
        repo.update(id, CredentialDraft(title = "GitHub", username = "alice", password = "v2", webDomain = "github.com"))
        val bytes = backups.exportEncrypted("backup-pass-123".toCharArray())

        manager.destroy() // "wipe app data"
        manager.create(pw.copyOf())
        val report = backups.importEncrypted(bytes, "backup-pass-123".toCharArray())
        assertThat(report.added).isEqualTo(1)
        val restored = repo.get(id)!!
        assertThat(restored.password).isEqualTo("v2")
        assertThat(session.databaseOrNull()!!.credentials().getHistory(id).map { it.password }).containsExactly("v1")
        // Importing the same backup again changes nothing.
        assertThat(backups.importEncrypted(bytes, "backup-pass-123".toCharArray()).skipped).isEqualTo(1)
    }
}
