package com.ares.vault.core.generator

import com.nulabinc.zxcvbn.Zxcvbn
import java.nio.CharBuffer

/**
 * zxcvbn score (0–4) for passwords the user types themselves (master password, manual
 * entries). Generated passwords use [PasswordGenerator.entropyBits] instead, because
 * zxcvbn estimates guessability of a string, not the strength of a random process.
 */
class StrengthMeter {
    data class Result(val score: Int, val guessesLog10: Double, val warning: String?, val suggestions: List<String>)

    private val zxcvbn by lazy { Zxcvbn() }

    fun measure(password: CharArray, userInputs: List<String> = emptyList()): Result {
        // CharBuffer.wrap avoids making a String; zxcvbn's internal copies are wiped below.
        val strength = zxcvbn.measure(CharBuffer.wrap(password), userInputs)
        return try {
            Result(
                score = strength.score,
                guessesLog10 = strength.guessesLog10,
                warning = strength.feedback.warning?.takeIf { it.isNotBlank() },
                suggestions = strength.feedback.suggestions.orEmpty(),
            )
        } finally {
            strength.wipe()
        }
    }

    companion object {
        const val MASTER_MIN_LENGTH = 10
        const val MASTER_MIN_SCORE = 3
    }
}
