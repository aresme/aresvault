package com.ares.vault.data.settings

import android.content.Context
import androidx.core.content.edit
import com.ares.vault.core.generator.GeneratorSettings
import com.ares.vault.data.AresLog
import com.ares.vault.data.session.VaultSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generator preferences. The source of truth is the encrypted settings row; a copy is
 * mirrored to plain prefs because autofill's "Generate strong password" must work while the
 * vault is locked. Generator options (lengths, character classes) are not secret.
 */
@Singleton
class GeneratorSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val session: VaultSession,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mirror = context.getSharedPreferences("generator", Context.MODE_PRIVATE)

    suspend fun load(): GeneratorSettings {
        val raw = session.databaseOrNull()?.settings()?.get()?.generatorConfig?.takeIf { it.isNotBlank() }
            ?: mirror.getString(KEY, null)
        return decode(raw)
    }

    /** Usable while locked (reads only the mirror). */
    fun loadForAutofill(): GeneratorSettings = decode(mirror.getString(KEY, null))

    /** Returns false if the vault is locked (only the mirror is updated then). */
    suspend fun save(settings: GeneratorSettings): Boolean {
        val encoded = json.encodeToString(GeneratorSettings.serializer(), settings)
        mirror.edit { putString(KEY, encoded) }
        val dao = session.databaseOrNull()?.settings() ?: return false
        val current = dao.get() ?: return false
        dao.upsert(current.copy(generatorConfig = encoded))
        return true
    }

    private fun decode(raw: String?): GeneratorSettings {
        if (raw.isNullOrBlank()) return GeneratorSettings()
        return try {
            json.decodeFromString(GeneratorSettings.serializer(), raw)
        } catch (e: SerializationException) {
            AresLog.w("GeneratorSettings", "Stored generator settings unreadable; using defaults", e)
            GeneratorSettings()
        }
    }

    private companion object {
        const val KEY = "settings_json"
    }
}
