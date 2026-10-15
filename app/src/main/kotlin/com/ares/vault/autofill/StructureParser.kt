package com.ares.vault.autofill

import android.app.assist.AssistStructure
import android.os.Build
import android.view.View
import android.view.autofill.AutofillId
import com.ares.vault.core.autofill.FieldInfo

class ParsedField(val id: AutofillId, val info: FieldInfo, val node: AssistStructure.ViewNode)

class ParsedStructure(
    val packageName: String,
    val activityClass: String,
    val fields: List<ParsedField>,
    /** First webDomain reported by any node; only trusted for allowlisted browsers. */
    val webDomain: String?,
    val webScheme: String?,
    /** Non-input page text (titles, buttons) for sign-up keyword detection. */
    val pageText: String,
) {
    fun field(id: AutofillId?): ParsedField? = id?.let { fields.firstOrNull { f -> f.id == it } }
}

/** Flattens an AssistStructure into [FieldInfo]s. Reads field *values* only via [textValue]. */
object StructureParser {
    private const val MAX_PAGE_TEXT = 4000

    fun parse(structure: AssistStructure): ParsedStructure {
        val fields = mutableListOf<ParsedField>()
        val pageText = StringBuilder()
        var webDomain: String? = null
        var webScheme: String? = null

        fun walk(node: AssistStructure.ViewNode, parentVisible: Boolean) {
            val visible = parentVisible && node.visibility == View.VISIBLE
            if (webDomain == null && !node.webDomain.isNullOrBlank()) {
                webDomain = node.webDomain
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) webScheme = node.webScheme
            }
            val html = node.htmlInfo
            val id = node.autofillId
            if (id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
                val attrs = html?.attributes.orEmpty()
                    .mapNotNull { p -> p.first?.lowercase()?.let { it to (p.second ?: "") } }
                    .toMap()
                val tag = html?.tag?.lowercase()
                fields += ParsedField(
                    id,
                    FieldInfo(
                        autofillHints = node.autofillHints?.toList().orEmpty(),
                        htmlTag = tag,
                        htmlAttributes = attrs,
                        inputType = node.inputType,
                        idEntry = node.idEntry,
                        hint = node.hint,
                        className = node.className,
                        visible = visible,
                        // Web inputs are focusable even when the flag isn't reported.
                        focusable = node.isFocusable || tag == "input",
                        focused = node.isFocused,
                        isTextInput = tag == "input" || node.className?.contains("EditText") == true || node.inputType != 0,
                    ),
                    node,
                )
            } else if (visible && pageText.length < MAX_PAGE_TEXT) {
                node.text?.takeIf { it.isNotBlank() }?.let { pageText.append(it).append(' ') }
                node.contentDescription?.takeIf { it.isNotBlank() }?.let { pageText.append(it).append(' ') }
            }
            for (i in 0 until node.childCount) walk(node.getChildAt(i), visible)
        }

        for (w in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(w).rootViewNode, true)

        val component = structure.activityComponent
        return ParsedStructure(
            packageName = component.packageName,
            activityClass = component.className,
            fields = fields,
            webDomain = webDomain,
            webScheme = webScheme,
            pageText = pageText.toString(),
        )
    }

    /** The typed value of a field (save flow only). */
    fun textValue(field: ParsedField): String? =
        field.node.autofillValue?.takeIf { it.isText }?.textValue?.toString()
}
