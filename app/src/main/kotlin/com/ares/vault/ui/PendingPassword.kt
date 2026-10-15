package com.ares.vault.ui

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a generated password from the generator to a new entry *in memory*. Passing it as
 * a navigation argument would put it into saved-instance state, which can be persisted.
 */
@Singleton
class PendingPassword @Inject constructor() {
    private var value: CharArray? = null

    fun put(password: CharArray) {
        value?.fill('\u0000')
        value = password.copyOf()
    }

    /** Returns and forgets the pending password. */
    fun take(): CharArray? = value.also { value = null }
}
