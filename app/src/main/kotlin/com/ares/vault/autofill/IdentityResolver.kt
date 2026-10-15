package com.ares.vault.autofill

import android.content.Context
import com.ares.vault.core.autofill.BrowserAllowlist
import com.ares.vault.core.autofill.ClientIdentity
import com.ares.vault.core.domain.Domains
import com.ares.vault.data.AresLog
import com.ares.vault.data.apps.AppSignatures
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the requesting app into a [ClientIdentity] (spec §4.4):
 * - allowlisted browser *with its genuine signature* + webDomain → website identity;
 * - anything else → app identity (package + current signing cert). A webDomain reported by a
 *   non-browser (e.g. an app's WebView pretending to be bank.com) is ignored.
 */
@Singleton
class IdentityResolver @Inject constructor(@ApplicationContext private val context: Context) {
    private val allowlist get() = BrowserAllowlist.default

    fun resolve(parsed: ParsedStructure): ClientIdentity {
        val pkg = parsed.packageName
        val cert = AppSignatures.currentSha256(context.packageManager, pkg)
        val domain = parsed.webDomain
        if (domain != null) {
            if (allowlist.isTrustedBrowser(pkg, cert)) {
                val scheme = parsed.webScheme?.lowercase()
                val host = Domains.normalizeHost(domain)
                if (host != null && (scheme == null || scheme == "https" || scheme == "http")) {
                    return ClientIdentity.Web(host, pkg)
                }
            } else if (allowlist.isKnownBrowserPackage(pkg)) {
                AresLog.w(COMPONENT, "Browser package $pkg has an unexpected signature; ignoring its webDomain")
            } else {
                AresLog.i(COMPONENT, "Ignoring webDomain reported by non-browser app $pkg")
            }
        }
        return ClientIdentity.App(pkg, cert)
    }

    fun certMatches(packageName: String, storedCert: String): Boolean =
        AppSignatures.matches(context.packageManager, packageName, storedCert)

    private companion object {
        const val COMPONENT = "Autofill"
    }
}
