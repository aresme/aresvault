package com.ares.vault.passkey

import android.content.pm.PackageManager
import androidx.credentials.provider.CallingAppInfo
import com.ares.vault.core.autofill.BrowserAllowlist
import com.ares.vault.core.passkey.PasskeyException
import com.ares.vault.core.passkey.WebAuthn
import java.security.MessageDigest

/** Who is asking for a passkey operation, and the WebAuthn origin to put in clientDataJSON. */
sealed interface PasskeyCaller {
    val packageName: String
    val origin: String

    /** A verified browser vouching for a web origin. */
    data class Browser(override val packageName: String, override val origin: String) : PasskeyCaller

    /** A native app: origin is its signing-cert hash; needs the user's one-time approval per rpId. */
    data class App(override val packageName: String, override val origin: String, val certSha256: String) : PasskeyCaller

    companion object {
        private val privilegedJson by lazy { BrowserAllowlist.default.toPrivilegedAppsJson() }

        fun resolve(info: CallingAppInfo): PasskeyCaller {
            if (info.isOriginPopulated()) {
                // Only allowlisted, correctly signed browsers may claim a web origin.
                val origin = try {
                    info.getOrigin(privilegedJson)
                } catch (_: IllegalStateException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } ?: throw PasskeyException("${info.packageName} is not a browser AresVault trusts for passkeys")
                return Browser(info.packageName, origin.trimEnd('/'))
            }
            val signers = info.signingInfo.let { s ->
                if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
            } ?: throw PasskeyException("Can't read the app's signature")
            val cert = signers.last().toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(cert)
            return App(
                packageName = info.packageName,
                origin = "android:apk-key-hash:" + WebAuthn.b64(digest),
                certSha256 = digest.joinToString("") { "%02x".format(it) },
            )
        }

        fun label(pm: PackageManager, pkg: String): String = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}
