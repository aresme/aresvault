package com.ares.vault.data.session

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.ares.vault.core.policy.VaultClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidVaultClock @Inject constructor(@ApplicationContext private val context: Context) : VaultClock {
    override fun wallMillis() = System.currentTimeMillis()
    override fun elapsedMillis() = SystemClock.elapsedRealtime()
    override fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
}
