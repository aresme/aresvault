package com.ares.vault.core.autofill

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Browsers whose reported `webDomain` we trust (spec §4.4). An app can put any string in
 * a WebView's webDomain, so only a known browser package *signed by its real developer*
 * may make us treat a request as coming from a website.
 */
class BrowserAllowlist(private val certsByPackage: Map<String, Set<String>>) {

    /** [certSha256] may be a comma-joined multi-signer digest from AppSignatures. */
    fun isTrustedBrowser(packageName: String, certSha256: String?): Boolean {
        val allowed = certsByPackage[packageName] ?: return false
        if (certSha256 == null) return false
        return certSha256.split(',').any { it.lowercase() in allowed }
    }

    fun isKnownBrowserPackage(packageName: String) = packageName in certsByPackage

    val packages: Set<String> get() = certsByPackage.keys

    /**
     * The allowlist in Google's "privileged apps" JSON format, as expected by
     * Credential Manager's CallingAppInfo.getOrigin(): only these browsers may assert a web origin.
     */
    fun toPrivilegedAppsJson(): String = buildString {
        append("{\"apps\":[")
        certsByPackage.entries.forEachIndexed { i, (pkg, certs) ->
            if (i > 0) append(',')
            append("{\"type\":\"android\",\"info\":{\"package_name\":\"").append(pkg).append("\",\"signatures\":[")
            certs.forEachIndexed { j, c ->
                if (j > 0) append(',')
                val colon = c.uppercase().chunked(2).joinToString(":")
                append("{\"build\":\"release\",\"cert_fingerprint_sha256\":\"").append(colon).append("\"}")
            }
            append("]}}")
        }
        append("]}")
    }

    @Serializable
    private data class File(val source: String, val browsers: List<Entry>)

    @Serializable
    private data class Entry(val `package`: String, val certs: List<String>)

    companion object {
        // Absolute path: R8 moves classes to other packages, which breaks relative lookups.
        private const val RESOURCE = "/com/ares/vault/core/autofill/browsers.json"

        val default: BrowserAllowlist by lazy {
            val text = checkNotNull(BrowserAllowlist::class.java.getResourceAsStream(RESOURCE)) {
                "browsers.json missing"
            }.bufferedReader().use { it.readText() }
            val file = Json.decodeFromString(File.serializer(), text)
            BrowserAllowlist(file.browsers.associate { it.`package` to it.certs.map(String::lowercase).toSet() })
        }
    }
}
