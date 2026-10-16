package com.ares.vault.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ares.vault.backup.AutoBackupFiles
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class AutoBackupFilesTest {
    @Test fun keepsNewestAutomaticFilesOnly() {
        val names = listOf(
            "AresVault-auto-2026-10-01_10-00-00.aresvault",
            "AresVault-auto-2026-10-16_09-00-00.aresvault",
            "AresVault-auto-2026-10-15_09-00-00.aresvault",
            "AresVault-2026-10-16.aresvault", // manual backup: never pruned
            "notes.txt",
        )
        assertThat(AutoBackupFiles.toDelete(names, keep = 2)).containsExactly("AresVault-auto-2026-10-01_10-00-00.aresvault")
        assertThat(AutoBackupFiles.toDelete(names, keep = 5)).isEmpty()
        assertThat(AutoBackupFiles.toDelete(names, keep = 0)).hasSize(2) // always keeps at least one
    }

    @Test fun namesSortChronologicallyAndFolderNamesAreReadable() {
        assertThat(AutoBackupFiles.name(LocalDateTime.of(2026, 10, 16, 15, 4, 5))).isEqualTo("AresVault-auto-2026-10-16_15-04-05.aresvault")
        assertThat(AutoBackupFiles.displayName("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FAresVault"))
            .isEqualTo("Download/AresVault")
    }
}
