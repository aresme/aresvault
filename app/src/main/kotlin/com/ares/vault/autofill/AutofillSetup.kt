package com.ares.vault.autofill

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager

/** Whether AresVault is the system autofill service, and how to make it so (spec §6.5). */
object AutofillSetup {
    fun isEnabled(context: Context): Boolean =
        context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true

    fun isSupported(context: Context): Boolean =
        context.getSystemService(AutofillManager::class.java)?.isAutofillSupported == true

    /** Opens the system "Use AresVault for autofill?" picker. */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:${context.packageName}"))

    const val CHROME_STEPS =
        "In Chrome: ⋮ menu → Settings → Autofill services → choose “Autofill using another service”, " +
            "then restart Chrome. Do the same in Brave and Edge if you use them. Firefox works without extra steps."
}
