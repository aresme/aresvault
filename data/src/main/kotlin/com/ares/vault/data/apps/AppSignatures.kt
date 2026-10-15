package com.ares.vault.data.apps

import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * Reads an installed app's signing certificate digest (spec §4.4). This, not the package
 * name alone, is what proves an app's identity: any app can claim any package name on a
 * sideloaded build, but cannot forge the developer's signature.
 */
object AppSignatures {

    /**
     * SHA-256 of the current signing certificate(s), lowercase hex. Multiple signers are
     * sorted and comma-joined. Null if the package is not installed/visible.
     */
    fun currentSha256(pm: PackageManager, packageName: String): String? {
        val signatures = signatures(pm, packageName) ?: return null
        if (signatures.isEmpty()) return null
        return signatures.map { sha256(it.toByteArray()) }.sorted().joinToString(",")
    }

    /**
     * True if [certSha256] is the app's current certificate or (API 28+) an earlier
     * certificate in its verified rotation lineage — so a legitimate key rotation by the
     * developer keeps autofill working.
     */
    fun matches(pm: PackageManager, packageName: String, certSha256: String): Boolean {
        val current = currentSha256(pm, packageName) ?: return false
        if (current == certSha256) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ',' !in certSha256) {
            val bytes = hexToBytes(certSha256) ?: return false
            return pm.hasSigningCertificate(packageName, bytes, PackageManager.CERT_INPUT_SHA256)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun signatures(pm: PackageManager, packageName: String): Array<Signature>? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return null
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory?.takeLast(1)?.toTypedArray()
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
