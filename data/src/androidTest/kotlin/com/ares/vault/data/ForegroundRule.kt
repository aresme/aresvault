package com.ares.vault.data

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import org.junit.rules.ExternalResource

/** Visible window that keeps the test process in the foreground. */
class ForegroundTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/**
 * Infinix XOS ("Usf_Hiber") freezes processes without a visible window about a second
 * after they start, which stalls long instrumented tests. Holding an activity open keeps
 * the test process foreground for the duration of each test.
 */
class ForegroundRule : ExternalResource() {
    private var scenario: ActivityScenario<ForegroundTestActivity>? = null

    override fun before() {
        scenario = ActivityScenario.launch(ForegroundTestActivity::class.java)
    }

    override fun after() {
        scenario?.close()
    }
}
