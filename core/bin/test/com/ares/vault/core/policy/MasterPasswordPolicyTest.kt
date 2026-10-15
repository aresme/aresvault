package com.ares.vault.core.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MasterPasswordPolicyTest {
    private val policy = MasterPasswordPolicy()

    @Test fun rejectsShortWeakOrMismatched() {
        assertThat(policy.evaluate("Xy7#kP2!".toCharArray(), null).longEnough).isFalse()
        assertThat(policy.evaluate("password1234".toCharArray(), null).strongEnough).isFalse()
        assertThat(policy.evaluate("aresvault2026".toCharArray(), null).acceptable).isFalse()
        val strong = "tundra-mango-violet-42".toCharArray()
        assertThat(policy.evaluate(strong, "tundra-mango-violet-43".toCharArray()).matches).isFalse()
    }

    @Test fun acceptsStrongMatching() {
        val pw = "Tundra-mango-quiver-cascade-42"
        val e = policy.evaluate(pw.toCharArray(), pw.toCharArray())
        assertThat(e.acceptable).isTrue()
        assertThat(e.score).isAtLeast(3)
    }
}
