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
