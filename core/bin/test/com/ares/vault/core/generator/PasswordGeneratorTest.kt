package com.ares.vault.core.generator

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.log2

class PasswordGeneratorTest {
    private val gen = PasswordGenerator(seededRandom())

    private fun CharArray.has(set: String) = any { it in set }

    @Test fun presetsHaveLengthAndEveryEnabledClass() {
        for (preset in listOf(Preset.MEDIUM, Preset.STRONG, Preset.VERY_STRONG, Preset.MAXIMUM)) {
            val config = Preset.fixedConfig(preset)!!
            repeat(2_000) {
                val pw = gen.random(config)
                assertThat(pw.size).isEqualTo(config.length)
                assertThat(pw.has(CharSets.UPPER)).isTrue()
                assertThat(pw.has(CharSets.LOWER)).isTrue()
                assertThat(pw.has(CharSets.DIGITS)).isTrue()
                assertThat(pw.has(CharSets.SYMBOLS)).isEqualTo(config.symbols)
            }
        }
        assertThat(Preset.fixedConfig(Preset.MEDIUM)!!.length).isEqualTo(12)
        assertThat(Preset.fixedConfig(Preset.STRONG)!!.length).isEqualTo(16)
        assertThat(Preset.fixedConfig(Preset.VERY_STRONG)!!.length).isEqualTo(24)
        assertThat(Preset.fixedConfig(Preset.MAXIMUM)!!.length).isEqualTo(32)
    }

    @Test fun disabledClassesNeverAppear() {
        val config = RandomConfig(length = 40, upper = false, lower = true, digits = true, symbols = false, minSymbols = 0)
        repeat(2_000) {
            val pw = gen.random(config)
            assertThat(pw.has(CharSets.UPPER)).isFalse()
            assertThat(pw.has(CharSets.SYMBOLS)).isFalse()
        }
    }

    @Test fun minimumDigitsAndSymbolsAreHonoured() {
        val config = RandomConfig(length = 8, minDigits = 3, minSymbols = 3)
        repeat(5_000) {
            val pw = gen.random(config)
            assertThat(pw.count { it in CharSets.DIGITS }).isAtLeast(3)
            assertThat(pw.count { it in CharSets.SYMBOLS }).isAtLeast(3)
        }
    }

    @Test fun avoidAmbiguousRemovesLookAlikes() {
        val config = RandomConfig(length = 64, avoidAmbiguous = true)
        repeat(2_000) { assertThat(gen.random(config).has(CharSets.AMBIGUOUS)).isFalse() }
    }

    @Test fun invalidConfigsAreRejected() {
        val bad = listOf(
            RandomConfig(length = 7),
            RandomConfig(length = 65),
            RandomConfig(upper = false, lower = false, digits = false, symbols = false),
            RandomConfig(length = 8, minDigits = 5, minSymbols = 4),
        )
        bad.forEach { config ->
            assertThat(config.validationError()).isNotNull()
            assertThrows(IllegalArgumentException::class.java) { gen.random(config) }
        }
    }

    @Test fun withoutSymbolsDropsSymbolsOnly() {
        val config = RandomConfig(length = 16).withoutSymbols()
        assertThat(config.symbols).isFalse()
        assertThat(config.upper && config.lower && config.digits).isTrue()
        repeat(1_000) { assertThat(gen.random(config).has(CharSets.SYMBOLS)).isFalse() }
        val symbolsOnly = RandomConfig(upper = false, lower = false, digits = false, minDigits = 0)
        assertThat(symbolsOnly.withoutSymbols().validationError()).isNull()
    }

    @Test fun characterFrequenciesAreUniformOver100kSamples() {
        val config = RandomConfig(length = 10, upper = false, digits = false, symbols = false, minDigits = 0, minSymbols = 0)
        val counts = LongArray(26)
        repeat(100_000) { gen.random(config).forEach { counts[it - 'a']++ } }
        assertThat(chiSquare(counts.toList())).isLessThan(CHI2_P001.getValue(25))
    }

    @Test fun guaranteedCharactersAreNotPositionallyBiased() {
        // Exactly one digit per password (min 1, and digits are 10 of 36 chars, so most
        // passwords have more). The *first* digit's position must not favour the start.
        val config = RandomConfig(length = 16, upper = true, lower = false, digits = true, symbols = false, minSymbols = 0)
        val counts = LongArray(16)
        repeat(100_000) {
            gen.random(config).forEachIndexed { i, c -> if (c in CharSets.DIGITS) counts[i]++ }
        }
        assertThat(chiSquare(counts.toList())).isLessThan(CHI2_P001.getValue(15))
    }

    @Test fun noDuplicatesIn100kStrongPasswords() {
        val config = Preset.fixedConfig(Preset.STRONG)!!
        val seen = HashSet<String>(200_000)
        repeat(100_000) { assertThat(seen.add(String(gen.random(config)))).isTrue() }
    }

    @Test fun entropyMatchesPoolSize() {
        val pool = (CharSets.UPPER + CharSets.LOWER + CharSets.DIGITS + CharSets.SYMBOLS).length
        assertThat(PasswordGenerator.entropyBits(Preset.fixedConfig(Preset.STRONG)!!))
            .isWithin(1e-9).of(16 * log2(pool.toDouble()))
        assertThat(PasswordGenerator.entropyBits(Preset.fixedConfig(Preset.MEDIUM)!!))
            .isWithin(1e-9).of(12 * log2(62.0))
        val noAmbiguous = RandomConfig(length = 10, upper = false, symbols = false, avoidAmbiguous = true, minSymbols = 0)
        // lower without 'l' (25) + digits without 0/1 (8)
        assertThat(PasswordGenerator.entropyBits(noAmbiguous)).isWithin(1e-9).of(10 * log2(33.0))
    }

    @Test fun strengthLevelsLineUpWithPresetNames() {
        fun level(p: Preset) = StrengthLevel.forBits(PasswordGenerator.entropyBits(Preset.fixedConfig(p)!!))
        assertThat(level(Preset.MEDIUM)).isEqualTo(StrengthLevel.GOOD)
        assertThat(level(Preset.STRONG)).isEqualTo(StrengthLevel.STRONG)
        assertThat(level(Preset.VERY_STRONG)).isEqualTo(StrengthLevel.VERY_STRONG)
        assertThat(level(Preset.MAXIMUM)).isEqualTo(StrengthLevel.EXCELLENT)
        assertThat(StrengthLevel.forBits(30.0)).isEqualTo(StrengthLevel.WEAK)
        assertThat(StrengthLevel.fraction(256.0)).isEqualTo(1f)
    }
}
