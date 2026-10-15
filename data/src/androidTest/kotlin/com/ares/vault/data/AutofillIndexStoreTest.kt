package com.ares.vault.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ares.vault.data.autofill.AutofillIndexStore
import com.ares.vault.data.autofill.IndexEntry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AutofillIndexStoreTest {
    @get:Rule val foreground = ForegroundRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = AutofillIndexStore(context)

    @After fun clean() = store.delete()

    @Test fun roundTripAndEncryptedAtRest() {
        assertThat(store.read()).isNull()
        val entries = listOf(IndexEntry("1", "alice@example.com", "GitHub", webDomain = "github.com"))
        store.write(entries)
        assertThat(store.read()).isEqualTo(entries)
        val raw = File(context.noBackupFilesDir, "autofill_index.json").readText()
        assertThat(raw).doesNotContain("alice")
        assertThat(raw).doesNotContain("github")
    }

    @Test fun deleteRemovesIndexAndKey() {
        store.write(listOf(IndexEntry("1", "bob", "X")))
        store.delete()
        assertThat(store.read()).isNull()
    }
}
