package com.ares.vault.data.settings

import com.ares.vault.core.generator.GeneratorSettings
import com.ares.vault.data.AresLog
import com.ares.vault.data.session.VaultSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Generator preferences, stored in the encrypted settings row. Defaults while locked. */
@Singleton
class GeneratorSettingsRepository @Inject constructor(private val session: VaultSession) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): GeneratorSettings {
        val raw = session.databaseOrNull()?.settings()?.get()?.generatorConfig
        if (raw.isNullOrBlank()) return GeneratorSettings()
        return try {
            json.decodeFromString(GeneratorSettings.serializer(), raw)
        } catch (e: SerializationException) {
            AresLog.w("GeneratorSettings", "Stored generator settings unreadable; using defaults", e)
            GeneratorSettings()
        }
    }

    /** Returns false if the vault is locked (nothing is persisted then). */
    suspend fun save(settings: GeneratorSettings): Boolean {
        val dao = session.databaseOrNull()?.settings() ?: return false
        val current = dao.get() ?: return false
        dao.upsert(current.copy(generatorConfig = json.encodeToString(GeneratorSettings.serializer(), settings)))
        return true
    }
}
