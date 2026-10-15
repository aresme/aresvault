package com.ares.vault.data.crypto

import android.content.Context
import androidx.core.util.AtomicFile
import com.ares.vault.core.crypto.VaultHeader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the vault header in app-private, no-backup storage. Writes are atomic so a
 * crash mid-write can never leave a half-written header (which would brick the vault).
 */
@Singleton
class VaultHeaderStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    fun exists(): Boolean = file.baseFile.exists()

    /** Returns null if no vault has been created; throws InvalidHeaderException if corrupt. */
    fun read(): VaultHeader? = try {
        VaultHeader.decode(file.readFully())
    } catch (_: FileNotFoundException) {
        null
    }

    fun write(header: VaultHeader) {
        val out = file.startWrite()
        try {
            out.write(header.encode())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    fun delete() = file.delete()

    companion object {
        const val FILE_NAME = "vault_header.json"
    }
}
