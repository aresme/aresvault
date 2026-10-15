package com.ares.vault.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ares.vault.core.crypto.SqlCipherKey
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [CredentialEntity::class, PasswordHistoryEntity::class, SettingsEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun credentials(): CredentialDao
    abstract fun settings(): SettingsDao

    companion object {
        const val FILE_NAME = "vault.db"

        @Volatile private var nativeLoaded = false

        /**
         * Opens (or creates) the SQLCipher database with the DEK as a raw key.
         * Returns the database and the key literal; the caller wipes the literal on close.
         * Forces the open here so a wrong key fails immediately rather than on first query.
         */
        fun open(context: Context, dek: ByteArray): Pair<VaultDatabase, ByteArray> {
            if (!nativeLoaded) {
                System.loadLibrary("sqlcipher")
                nativeLoaded = true
            }
            val key = SqlCipherKey.rawKey(dek)
            // WAL off: a single connection, so SQLCipher never needs the key again
            // after this open (secondary WAL reader connections would re-key lazily).
            val factory = SupportOpenHelperFactory(key, null, false)
            val db = Room.databaseBuilder(context, VaultDatabase::class.java, FILE_NAME)
                .openHelperFactory(factory)
                .setJournalMode(JournalMode.TRUNCATE)
                .build()
            try {
                db.openHelper.writableDatabase
            } catch (e: Exception) {
                db.close()
                key.fill(0)
                throw e
            }
            return db to key
        }

        fun delete(context: Context) {
            context.deleteDatabase(FILE_NAME)
        }
    }
}
