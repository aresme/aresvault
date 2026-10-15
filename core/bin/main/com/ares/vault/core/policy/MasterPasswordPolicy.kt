package com.ares.vault.core.policy

import com.ares.vault.core.generator.StrengthMeter

/** Spec §4.1: master password must be ≥ 10 chars and score ≥ 3 on zxcvbn. */
class MasterPasswordPolicy(private val meter: StrengthMeter = StrengthMeter()) {

    data class Evaluation(
        val score: Int,
        val longEnough: Boolean,
        val strongEnough: Boolean,
        val matches: Boolean,
        val warning: String?,
        val suggestions: List<String>,
    ) {
        val acceptable: Boolean get() = longEnough && strongEnough && matches
    }

    fun evaluate(password: CharArray, confirm: CharArray?): Evaluation {
        val result = if (password.isEmpty()) null else meter.measure(password, listOf("aresvault", "ares", "vault"))
        return Evaluation(
            score = result?.score ?: 0,
            longEnough = password.size >= MIN_LENGTH,
            strongEnough = (result?.score ?: 0) >= MIN_SCORE,
            matches = confirm == null || password.contentEquals(confirm),
            warning = result?.warning,
            suggestions = result?.suggestions.orEmpty(),
        )
    }

    companion object {
        const val MIN_LENGTH = StrengthMeter.MASTER_MIN_LENGTH
        const val MIN_SCORE = StrengthMeter.MASTER_MIN_SCORE
    }
}
