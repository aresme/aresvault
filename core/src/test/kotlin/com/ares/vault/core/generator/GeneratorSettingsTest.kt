package com.ares.vault.core.generator

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class GeneratorSettingsTest {
    @Test fun defaultIsStrongAndRoundTripsThroughJson() {
        val settings = GeneratorSettings(
            defaultPreset = Preset.PASSPHRASE,
            custom = RandomConfig(length = 30, avoidAmbiguous = true),
            passphrase = PassphraseConfig(words = 7, separator = "."),
        )
        val json = Json.encodeToString(GeneratorSettings.serializer(), settings)
        assertThat(Json.decodeFromString(GeneratorSettings.serializer(), json)).isEqualTo(settings)
        assertThat(GeneratorSettings().defaultConfig()).isEqualTo(Preset.fixedConfig(Preset.STRONG))
        assertThat(settings.defaultConfig()).isEqualTo(settings.passphrase)
    }

    @Test fun polymorphicConfigRoundTrips() {
        val configs = listOf<GeneratorConfig>(RandomConfig(length = 9), PassphraseConfig(words = 4))
        configs.forEach {
            val json = Json.encodeToString(GeneratorConfig.serializer(), it)
            assertThat(Json.decodeFromString(GeneratorConfig.serializer(), json)).isEqualTo(it)
        }
    }
}
