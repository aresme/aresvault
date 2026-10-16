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

    /**
     * Header for the *next* DEK during a database-key rotation. It exists only between
     * "write pending header" and "promote it" — if the app dies in between, unlock uses it to
     * recover (see VaultManager.rotateDatabaseKey).
     */
    private val pending = AtomicFile(File(context.noBackupFilesDir, PENDING_FILE_NAME))

    fun exists(): Boolean = file.baseFile.exists()

    /** Returns null if no vault has been created; throws InvalidHeaderException if corrupt. */
    fun read(): VaultHeader? = try {
        VaultHeader.decode(file.readFully())
    } catch (_: FileNotFoundException) {
        null
    }

    fun write(header: VaultHeader) = write(file, header)

    fun readPending(): VaultHeader? = try {
        VaultHeader.decode(pending.readFully())
    } catch (_: FileNotFoundException) {
        null
    }

    fun writePending(header: VaultHeader) = write(pending, header)

    fun deletePending() = pending.delete()

    private fun write(target: AtomicFile, header: VaultHeader) {
        val out = target.startWrite()
        try {
            out.write(header.encode())
            target.finishWrite(out)
        } catch (e: Exception) {
            target.failWrite(out)
            throw e
        }
    }

    fun delete() {
        file.delete()
        pending.delete()
    }

    companion object {
        const val FILE_NAME = "vault_header.json"
        const val PENDING_FILE_NAME = "vault_header.pending.json"
    }
}
