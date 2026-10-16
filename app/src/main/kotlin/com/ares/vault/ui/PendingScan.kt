package com.ares.vault.ui

import javax.inject.Inject
import javax.inject.Singleton

/** Hands a scanned 2FA URI back to the screen that asked for it, in memory only (it contains the secret). */
@Singleton
class PendingScan @Inject constructor() {
    private var value: String? = null

    fun put(uri: String) {
        value = uri
    }

    fun take(): String? = value.also { value = null }
}
