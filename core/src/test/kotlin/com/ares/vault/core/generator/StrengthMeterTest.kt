package com.ares.vault.core.generator

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StrengthMeterTest {
    private val meter = StrengthMeter()

    @Test fun commonPasswordsScoreLow() {
        listOf("password123", "qwertyuiop", "iloveyou2024").forEach {
            assertThat(meter.measure(it.toCharArray()).score).isLessThan(StrengthMeter.MASTER_MIN_SCORE)
        }
    }

    @Test fun generatedPasswordsScoreHigh() {
        val gen = PasswordGenerator(seededRandom())
        repeat(20) {
            assertThat(meter.measure(gen.generate(PassphraseConfig())).score).isEqualTo(4)
            assertThat(meter.measure(gen.generate(RandomConfig(length = 16))).score).isEqualTo(4)
        }
    }

    @Test fun userInputsArePenalised() {
        val pw = "kiplangat-vault".toCharArray()
        val without = meter.measure(pw).guessesLog10
        val with = meter.measure(pw, listOf("kiplangat", "vault")).guessesLog10
        assertThat(with).isLessThan(without)
    }
}
