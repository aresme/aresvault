package com.ares.vault.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.ares.vault.data.repo.CredentialDraft
import com.ares.vault.data.repo.CredentialRepository
import com.ares.vault.data.session.VaultSession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialRepositoryTest {
    private val session = VaultSession()
    private val repo = CredentialRepository(session)
    private val draft = CredentialDraft(title = "GitHub", username = "alice", password = "pw-1", webDomain = "github.com")

    @Test fun flowsFollowLockState() = runTest {
        repo.observeAll().test {
            assertThat(awaitItem()).isEmpty() // locked

            session.openInMemory()
            assertThat(awaitItem()).isEmpty() // unlocked, empty vault
            repo.create(draft)
            assertThat(awaitItem().map { it.title }).containsExactly("GitHub")

            session.lock()
            assertThat(awaitItem()).isEmpty() // locking hides everything
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun updateKeepsPasswordHistoryAndNormalises() = runTest {
        session.openInMemory()
        val id = repo.create(draft.copy(title = "  GitHub  ", webDomain = "GitHub.com"))
        repo.update(id, draft.copy(password = "pw-2"), now = 5)
        repo.update(id, draft.copy(password = "pw-2", notes = "same password"), now = 6)

        val stored = repo.get(id)!!
        assertThat(stored.title).isEqualTo("GitHub")
        assertThat(stored.password).isEqualTo("pw-2")
        assertThat(stored.notes).isEqualTo("same password")
        assertThat(repo.observeHistory(id).first().map { it.password }).containsExactly("pw-1")
        session.lock()
    }

    @Test fun writesFailWhileLocked() = runTest {
        assertThrows(CredentialRepository.VaultLockedException::class.java) {
            kotlinx.coroutines.runBlocking { repo.create(draft) }
        }
    }
}
