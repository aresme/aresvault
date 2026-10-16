package com.ares.vault.data.repo

import com.ares.vault.data.db.PasskeyAppLinkEntity
import com.ares.vault.data.db.PasskeyEntity
import com.ares.vault.data.session.VaultSession
import com.ares.vault.data.session.VaultState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PasskeyRepository @Inject constructor(private val session: VaultSession) {
    private fun dao() = session.databaseOrNull()?.passkeys() ?: throw CredentialRepository.VaultLockedException()

    fun observeAll(): Flow<List<PasskeyEntity>> = session.state.flatMapLatest { s ->
        when (s) {
            is VaultState.Unlocked -> s.database.passkeys().observeAll().catch { emit(emptyList()) }
            VaultState.Locked -> flowOf(emptyList())
        }
    }

    suspend fun forRp(rpId: String) = dao().forRp(rpId.lowercase())
    suspend fun get(id: String) = dao().get(id)
    suspend fun byCredentialId(credentialId: String) = dao().byCredentialId(credentialId)
    suspend fun insert(p: PasskeyEntity) = dao().insert(p)
    suspend fun markUsed(id: String, count: Long) = dao().markUsed(id, count, System.currentTimeMillis())
    suspend fun delete(id: String) = dao().delete(id)
    suspend fun isAppLinked(rpId: String, pkg: String, cert: String) = dao().isLinked(rpId.lowercase(), pkg, cert) > 0
    suspend fun linkApp(rpId: String, pkg: String, cert: String) = dao().link(PasskeyAppLinkEntity(rpId.lowercase(), pkg, cert, System.currentTimeMillis()))
}
