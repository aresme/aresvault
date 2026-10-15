package com.ares.vault.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ares.vault.core.crypto.SecureBytes
import com.ares.vault.data.db.CredentialEntity
import com.ares.vault.data.db.VaultDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VaultDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before @After fun clean() = VaultDatabase.delete(context)

    private fun entity(id: String, password: String, now: Long = 1L) = CredentialEntity(
        id = id, title = "Example", username = "alice", password = password,
        webDomain = "example.com", extraUrls = listOf("https://a.example.com"),
        createdAt = now, updatedAt = now,
    )

    @Test fun dataPersistsAcrossReopenWithSameKey() = runTest {
        val dek = SecureBytes.random(32)
        val (db, _) = VaultDatabase.open(context, dek)
        db.credentials().insert(entity("1", "hunter2-secret"))
        db.close()

        val (db2, _) = VaultDatabase.open(context, dek)
        val stored = db2.credentials().get("1")!!
        assertThat(stored.password).isEqualTo("hunter2-secret")
        assertThat(stored.extraUrls).containsExactly("https://a.example.com")
        db2.close()
    }

    @Test fun wrongKeyCannotOpen() = runTest {
        val (db, _) = VaultDatabase.open(context, SecureBytes.random(32))
        db.credentials().insert(entity("1", "x"))
        db.close()
        assertThrows(Exception::class.java) { VaultDatabase.open(context, SecureBytes.random(32)) }
    }

    @Test fun fileIsNotPlaintextSqlite() = runTest {
        val (db, _) = VaultDatabase.open(context, SecureBytes.random(32))
        db.credentials().insert(entity("1", "plaintext-canary-value"))
        db.close()
        val bytes = File(context.getDatabasePath(VaultDatabase.FILE_NAME).path).readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertThat(text).doesNotContain("SQLite format 3")
        assertThat(text).doesNotContain("plaintext-canary-value")
    }

    @Test fun passwordHistoryKeepsLastFive() = runTest {
        val (db, _) = VaultDatabase.open(context, SecureBytes.random(32))
        val dao = db.credentials()
        dao.insert(entity("1", "p0"))
        for (i in 1..7) dao.updateKeepingHistory(entity("1", "p$i", now = i.toLong()))
        val history = dao.observeHistory("1").first().map { it.password }
        assertThat(history).containsExactly("p6", "p5", "p4", "p3", "p2").inOrder()
        assertThat(dao.get("1")!!.password).isEqualTo("p7")
        db.close()
    }
}
