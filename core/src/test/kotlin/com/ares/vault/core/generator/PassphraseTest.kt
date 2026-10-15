package com.ares.vault.core.generator

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import kotlin.math.log2

class PassphraseTest {
    private val gen = PasswordGenerator(seededRandom())
    private val words = EffWordlist.words.toSet()

    @Test fun wordlistIsTheOfficialEffLargeList() {
        assertThat(EffWordlist.words).hasSize(7776)
        assertThat(words).hasSize(7776)
        val bytes = checkNotNull(EffWordlist::class.java.getResourceAsStream("eff_large_wordlist.txt")).readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertThat(sha).isEqualTo("addd35536511597a02fa0a9ff1e5284677b8883b83e986e43f15a3db996b903e")
    }

    @Test fun structureMatchesConfig() {
        for (count in PassphraseConfig.MIN_WORDS..PassphraseConfig.MAX_WORDS) {
            // A few EFF words contain '-' (e.g. "drop-down"), so only split on separators
            // that never occur inside a word.
            for (sep in PassphraseConfig.SEPARATORS.filter { s -> s.isNotEmpty() && words.none { s in it } }) {
                val config = PassphraseConfig(words = count, separator = sep, capitalize = true, includeNumber = true)
                repeat(200) {
                    val parts = String(gen.passphrase(config)).split(sep)
                    assertThat(parts).hasSize(count)
                    assertThat(parts.count { p -> p.last().isDigit() }).isEqualTo(1)
                    parts.forEach { p ->
                        assertThat(p[0].isUpperCase()).isTrue()
                        assertThat(words).contains(p.trimEnd { it.isDigit() }.lowercase())
                    }
                }
            }
        }
    }

    @Test fun plainLowercaseWithoutNumber() {
        val config = PassphraseConfig(words = 6, separator = " ", capitalize = false, includeNumber = false)
        repeat(500) {
            val parts = String(gen.passphrase(config)).split(" ")
            assertThat(parts).hasSize(6)
            parts.forEach { assertThat(words).contains(it) }
        }
    }

    @Test fun hyphenSeparatorStillYieldsWordCount() {
        val config = PassphraseConfig(words = 5, separator = "-", capitalize = false, includeNumber = false)
        repeat(500) {
            val pw = String(gen.passphrase(config))
            assertThat(pw.count { it == '-' }).isAtLeast(4)
            assertThat(pw.all { it.isLowerCase() || it == '-' }).isTrue()
        }
    }

    @Test fun emptySeparatorJoinsDirectly() {
        val config = PassphraseConfig(words = 4, separator = "", capitalize = true, includeNumber = false)
        val pw = String(gen.passphrase(config))
        assertThat(pw.count { it.isUpperCase() }).isAtLeast(4)
        assertThat(pw).doesNotContain(" ")
    }

    @Test fun invalidConfigsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { gen.passphrase(PassphraseConfig(words = 3)) }
        assertThrows(IllegalArgumentException::class.java) { gen.passphrase(PassphraseConfig(words = 9)) }
        assertThrows(IllegalArgumentException::class.java) { gen.passphrase(PassphraseConfig(separator = "--")) }
    }

    @Test fun entropy() {
        val perWord = log2(7776.0)
        assertThat(PasswordGenerator.entropyBits(PassphraseConfig(words = 5, includeNumber = false)))
            .isWithin(1e-9).of(5 * perWord)
        assertThat(PasswordGenerator.entropyBits(PassphraseConfig(words = 5, includeNumber = true)))
            .isWithin(1e-9).of(5 * perWord + log2(50.0))
    }

    @Test fun wordChoiceIsUniform() {
        // Bucket the 7776 words into 10 equal-ish groups by index and chi-square them.
        val index = EffWordlist.words.withIndex().associate { it.value to it.index }
        val counts = LongArray(10)
        val config = PassphraseConfig(words = 8, separator = ".", capitalize = false, includeNumber = false)
        repeat(20_000) {
            String(gen.passphrase(config)).split(".").forEach { counts[index.getValue(it) * 10 / 7776]++ }
        }
        assertThat(chiSquare(counts.toList())).isLessThan(CHI2_P001.getValue(9))
    }
}
