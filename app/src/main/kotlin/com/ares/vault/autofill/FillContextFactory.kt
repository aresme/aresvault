package com.ares.vault.autofill

import android.app.assist.AssistStructure
import android.content.Context
import android.view.inputmethod.InlineSuggestionsRequest
import com.ares.vault.BuildConfig
import com.ares.vault.core.autofill.FormAnalyzer
import com.ares.vault.core.autofill.FormType
import com.ares.vault.data.AresLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** AssistStructure → [FillContext]. Shared by the service and the auth activity. */
@Singleton
class FillContextFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identities: IdentityResolver,
) {
    fun create(structure: AssistStructure, inlineRequest: InlineSuggestionsRequest?): FillContext? {
        val parsed = StructureParser.parse(structure)
        if (parsed.packageName == context.packageName && !isTestHarness(parsed.activityClass)) return null
        val analysis = FormAnalyzer.analyze(parsed.fields.map { it.id to it.info }, parsed.pageText)
        if (analysis.type == FormType.NONE) return null
        val identity = identities.resolve(parsed)
        AresLog.d(
            "Autofill",
            "${analysis.type} form in ${parsed.packageName} (${parsed.fields.size} fields) → " +
                identity.javaClass.simpleName,
        )
        return FillContext(parsed, analysis, identity, inlineRequest)
    }

    private fun isTestHarness(activityClass: String) =
        BuildConfig.DEBUG && activityClass.endsWith(".AutofillTestActivity")
}
