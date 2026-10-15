package com.ares.vault.data

import android.util.Log

/**
 * Single log tag so `adb logcat -s AresVault` shows everything.
 * Never pass secrets (passwords, keys, usernames) to these functions.
 */
object AresLog {
    const val TAG = "AresVault"

    fun d(component: String, message: String) = Log.d(TAG, "[$component] $message")
    fun i(component: String, message: String) = Log.i(TAG, "[$component] $message")
    fun w(component: String, message: String, t: Throwable? = null) =
        Log.w(TAG, "[$component] $message", t)
    fun e(component: String, message: String, t: Throwable? = null) =
        Log.e(TAG, "[$component] $message", t)
}
