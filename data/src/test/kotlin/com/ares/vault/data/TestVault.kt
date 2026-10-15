package com.ares.vault.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ares.vault.core.policy.VaultClock
import com.ares.vault.data.db.VaultDatabase
import com.ares.vault.data.session.VaultSession

/** Unlocks [session] with a plain in-memory Room DB (SQLCipher is native-only). */
fun VaultSession.openInMemory(): VaultDatabase {
    val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VaultDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    open(db, ByteArray(32) { 1 }, ByteArray(67) { 2 })
    return db
}

class FakeClock(var elapsed: Long = 1_000_000L) : VaultClock {
    override fun wallMillis() = elapsed + 1_700_000_000_000L
    override fun elapsedMillis() = elapsed
    override fun bootCount() = 1
}
