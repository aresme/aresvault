package com.ares.vault

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ares.vault.autofill.AutofillIndexUpdater
import com.ares.vault.clipboard.SecureClipboard
import com.ares.vault.data.session.AutoLockController
import com.ares.vault.data.session.VaultSession
import com.ares.vault.data.session.VaultState
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AresVaultApp : Application() {
    @Inject lateinit var autoLock: AutoLockController
    @Inject lateinit var session: VaultSession
    @Inject lateinit var clipboard: SecureClipboard
    @Inject lateinit var autofillIndex: AutofillIndexUpdater

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // Background/foreground of the whole app (all activities), for auto-lock.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = autoLock.onAppForeground()
            override fun onStop(owner: LifecycleOwner) = autoLock.onAppBackground()
        })

        // SCREEN_OFF is only delivered to dynamically registered receivers.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = autoLock.onScreenOff()
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        autofillIndex.start()

        // Locking also clears any password we left on the clipboard.
        appScope.launch {
            session.state.drop(1).collect { if (it is VaultState.Locked) clipboard.clearIfOurs() }
        }
    }
}
