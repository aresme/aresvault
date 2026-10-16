package com.ares.vault.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.drawable.Icon
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.ares.vault.MainActivity
import com.ares.vault.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Builds autofill presentations and datasets, hiding the API 30/33 differences. */
@Singleton
class DatasetFactory @Inject constructor(@ApplicationContext private val context: Context) {
    private val requestCodes = AtomicInteger(1000)

    /** Dropdown row (all API levels). */
    fun menu(title: String, subtitle: String?, @DrawableRes icon: Int = R.drawable.ic_autofill_key): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_subtitle, subtitle.orEmpty())
            setViewVisibility(R.id.autofill_subtitle, if (subtitle.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
            setImageViewResource(R.id.autofill_icon, icon)
        }

    /** Keyboard-strip chip (API 30+, keyboards that support inline suggestions). */
    @RequiresApi(Build.VERSION_CODES.R)
    fun inline(
        spec: InlinePresentationSpec,
        title: String,
        subtitle: String?,
        @DrawableRes icon: Int = R.drawable.ic_autofill_key,
        pinned: Boolean = false,
    ): InlinePresentation? {
        if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) return null
        val attribution = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val content = InlineSuggestionUi.newContentBuilder(attribution)
            .setTitle(title)
            .apply { subtitle?.takeIf { it.isNotEmpty() }?.let { setSubtitle(it) } }
            .setStartIcon(Icon.createWithResource(context, icon))
            .setContentDescription(listOfNotNull(title, subtitle).joinToString(", "))
            .build()
        return InlinePresentation(content.slice, spec, pinned)
    }

    /**
     * A dataset with presentations. [values] maps field → text; null values are allowed only
     * with [auth] (the real values come back from the auth activity).
     */
    fun dataset(
        values: Map<AutofillId, String?>,
        menu: RemoteViews,
        inline: InlinePresentation?,
        auth: IntentSender? = null,
    ): Dataset {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Dataset.Builder(presentations(menu, inline)).also { b ->
                values.forEach { (id, v) -> b.setField(id, field(v)) }
            }
        } else {
            legacyDataset(values, menu, inline)
        }
        auth?.let { builder.setAuthentication(it) }
        return builder.build()
    }

    /** Dataset returned from an auth activity: values only, no presentation. */
    fun authResult(values: Map<AutofillId, String>): Dataset =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Dataset.Builder().also { b -> values.forEach { (id, v) -> b.setField(id, field(v)) } }.build()
        } else {
            @Suppress("DEPRECATION")
            Dataset.Builder().also { b -> values.forEach { (id, v) -> b.setValue(id, AutofillValue.forText(v)) } }.build()
        }

    /** Whole-response authentication ("Unlock AresVault"). */
    fun authResponse(ids: Array<AutofillId>, sender: IntentSender, menu: RemoteViews, inline: InlinePresentation?, saveInfo: SaveInfo?): FillResponse {
        val builder = FillResponse.Builder()
        saveInfo?.let { builder.setSaveInfo(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.setAuthentication(ids, sender, presentations(menu, inline))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inline != null) {
            @Suppress("DEPRECATION")
            builder.setAuthentication(ids, sender, menu, inline)
        } else {
            @Suppress("DEPRECATION")
            builder.setAuthentication(ids, sender, menu)
        }
        return builder.build()
    }

    /** IntentSender that opens the auth activity. Mutable: the system adds the AssistStructure. */
    fun authSender(mode: String, credentialId: String? = null): IntentSender =
        mutableSender(
            Intent(context, AutofillAuthActivity::class.java)
                .putExtra(AutofillAuthActivity.EXTRA_MODE, mode)
                .putExtra(AutofillAuthActivity.EXTRA_CREDENTIAL_ID, credentialId),
        )

    fun generateSender(): IntentSender = mutableSender(Intent(context, GeneratePasswordActivity::class.java))

    /** Started by the system after "Save"; carries only a token. */
    fun saveSender(token: String): IntentSender = PendingIntent.getActivity(
        context,
        requestCodes.incrementAndGet(),
        Intent(context, SaveActivity::class.java).putExtra(SaveActivity.EXTRA_TOKEN, token),
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    ).intentSender

    private fun mutableSender(intent: Intent): IntentSender {
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getActivity(context, requestCodes.incrementAndGet(), intent, flags).intentSender
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun presentations(menu: RemoteViews, inline: InlinePresentation?): Presentations =
        Presentations.Builder().setMenuPresentation(menu).apply { inline?.let { setInlinePresentation(it) } }.build()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun field(value: String?): Field =
        Field.Builder().apply { value?.let { setValue(AutofillValue.forText(it)) } }.build()

    @Suppress("DEPRECATION")
    private fun legacyDataset(values: Map<AutofillId, String?>, menu: RemoteViews, inline: InlinePresentation?): Dataset.Builder {
        val b = Dataset.Builder(menu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inline != null) b.setInlinePresentation(inline)
        values.forEach { (id, v) -> b.setValue(id, v?.let { AutofillValue.forText(it) }) }
        return b
    }
}
