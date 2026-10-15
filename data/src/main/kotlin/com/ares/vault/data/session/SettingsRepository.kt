package com.ares.vault.data.session

import com.ares.vault.data.db.SettingsEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/** Settings stored in the encrypted DB; null while locked. */
@Singleton
class SettingsRepository @Inject constructor(private val session: VaultSession) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<SettingsEntity?> = session.state.flatMapLatest { state ->
        when (state) {
            is VaultState.Unlocked -> state.database.settings().observe().catch { emit(null) }
            VaultState.Locked -> flowOf(null)
        }
    }

    suspend fun get(): SettingsEntity? = session.databaseOrNull()?.settings()?.get()

    suspend fun update(transform: (SettingsEntity) -> SettingsEntity): Boolean {
        val dao = session.databaseOrNull()?.settings() ?: return false
        dao.upsert(transform(dao.get() ?: SettingsEntity()))
        return true
    }
}
