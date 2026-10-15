package com.ares.vault.core.generator

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object CharSets {
    const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    const val DIGITS = "0123456789"

    /**
     * Symbols accepted by most sites. Quotes, backslash, backtick, space and pipe are left
     * out because forms and shell-like inputs often mangle them.
     */
    const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.<>?/~"

    /** Look-alikes removed when "avoid ambiguous characters" is on (0/O, l/1/I). */
    const val AMBIGUOUS = "0O1lI"
}

/** How a password is generated. Serialised into the encrypted settings table. */
@Serializable
sealed interface GeneratorConfig

@Serializable
@SerialName("random")
data class RandomConfig(
    val length: Int = 16,
    val upper: Boolean = true,
    val lower: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val avoidAmbiguous: Boolean = false,
    val minDigits: Int = 1,
    val minSymbols: Int = 1,
) : GeneratorConfig {

    /** Character pools for each enabled class, ambiguity filter applied. */
    fun pools(): List<Pool> = buildList {
        fun add(enabled: Boolean, chars: String, min: Int) {
            if (!enabled) return
            val filtered = if (avoidAmbiguous) chars.filterNot { it in CharSets.AMBIGUOUS } else chars
            add(Pool(filtered, min.coerceAtLeast(1)))
        }
        add(upper, CharSets.UPPER, 1)
        add(lower, CharSets.LOWER, 1)
        add(digits, CharSets.DIGITS, minDigits)
        add(symbols, CharSets.SYMBOLS, minSymbols)
    }

    /** Problem with this config, or null if it can be generated. */
    fun validationError(): String? {
        val pools = pools()
        return when {
            length !in MIN_LENGTH..MAX_LENGTH -> "Length must be $MIN_LENGTH–$MAX_LENGTH"
            pools.isEmpty() -> "Enable at least one character type"
            pools.sumOf { it.min } > length -> "Minimum counts exceed the length"
            else -> null
        }
    }

    fun withoutSymbols(): RandomConfig =
        if (!symbols) this else copy(symbols = false, minSymbols = 0).let {
            if (it.pools().isEmpty()) it.copy(lower = true) else it
        }

    data class Pool(val chars: String, val min: Int)

    companion object {
        const val MIN_LENGTH = 8
        const val MAX_LENGTH = 64
    }
}

@Serializable
@SerialName("passphrase")
data class PassphraseConfig(
    val words: Int = 5,
    val separator: String = "-",
    val capitalize: Boolean = true,
    val includeNumber: Boolean = true,
) : GeneratorConfig {
    fun validationError(): String? = when {
        words !in MIN_WORDS..MAX_WORDS -> "Use $MIN_WORDS–$MAX_WORDS words"
        separator.length > 1 -> "Separator must be a single character"
        else -> null
    }

    companion object {
        const val MIN_WORDS = 4
        const val MAX_WORDS = 8
        val SEPARATORS = listOf("-", ".", "_", " ", ",", "")
    }
}

/** Spec §6.1 strength presets. */
@Serializable
enum class Preset(val label: String) {
    MEDIUM("Medium"),
    STRONG("Strong"),
    VERY_STRONG("Very strong"),
    MAXIMUM("Maximum"),
    CUSTOM("Custom"),
    PASSPHRASE("Passphrase"),
    ;

    companion object {
        fun fixedConfig(preset: Preset): RandomConfig? = when (preset) {
            MEDIUM -> RandomConfig(length = 12, symbols = false, minSymbols = 0)
            STRONG -> RandomConfig(length = 16)
            VERY_STRONG -> RandomConfig(length = 24)
            MAXIMUM -> RandomConfig(length = 32)
            CUSTOM, PASSPHRASE -> null
        }
    }
}

/**
 * The user's generator preferences. [defaultPreset] is what autofill's
 * "Generate strong password" uses.
 */
@Serializable
data class GeneratorSettings(
    val defaultPreset: Preset = Preset.STRONG,
    val custom: RandomConfig = RandomConfig(length = 20),
    val passphrase: PassphraseConfig = PassphraseConfig(),
) {
    fun configFor(preset: Preset): GeneratorConfig = when (preset) {
        Preset.CUSTOM -> custom
        Preset.PASSPHRASE -> passphrase
        else -> Preset.fixedConfig(preset)!!
    }

    fun defaultConfig(): GeneratorConfig = configFor(defaultPreset)
}
