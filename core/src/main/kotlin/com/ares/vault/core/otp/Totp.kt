package com.ares.vault.core.otp

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class OtpAlgorithm(val jca: String, val uriName: String) {
    SHA1("HmacSHA1", "SHA1"),
    SHA256("HmacSHA256", "SHA256"),
    SHA512("HmacSHA512", "SHA512"),
}

/**
 * Time-based one-time password generator (RFC 6238, built on RFC 4226 HOTP). The HMAC comes
 * from the platform JCA; this class only does the counter/truncation arithmetic the RFCs specify.
 */
data class TotpSpec(
    val secret: ByteArray,
    val issuer: String = "",
    val account: String = "",
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = 6,
    val periodSeconds: Int = 30,
) {
    init {
        require(secret.size >= 10) { "Key is too short" }
        require(digits in 6..8) { "Digits must be 6–8" }
        require(periodSeconds in 10..300) { "Period out of range" }
    }

    fun code(timeMillis: Long): String = hotp(timeMillis / 1000 / periodSeconds)

    fun secondsRemaining(timeMillis: Long): Int = periodSeconds - ((timeMillis / 1000) % periodSeconds).toInt()

    /** RFC 4226 §5.3 dynamic truncation. */
    fun hotp(counter: Long): String {
        val mac = Mac.getInstance(algorithm.jca)
        mac.init(SecretKeySpec(secret, algorithm.jca))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        val mod = POW10[digits]
        return (binary % mod).toString().padStart(digits, '0')
    }

    /** Canonical `otpauth://totp/...` URI — the form stored (encrypted) in the vault. */
    fun toUri(): String {
        val label = listOf(issuer, account).filter { it.isNotEmpty() }.joinToString(":").ifEmpty { "AresVault" }
        val params = buildList {
            add("secret=${Base32.encode(secret)}")
            if (issuer.isNotEmpty()) add("issuer=${enc(issuer)}")
            if (algorithm != OtpAlgorithm.SHA1) add("algorithm=${algorithm.uriName}")
            if (digits != 6) add("digits=$digits")
            if (periodSeconds != 30) add("period=$periodSeconds")
        }
        return "otpauth://totp/${enc(label)}?${params.joinToString("&")}"
    }

    override fun equals(other: Any?) = other is TotpSpec && secret.contentEquals(other.secret) && issuer == other.issuer &&
        account == other.account && algorithm == other.algorithm && digits == other.digits && periodSeconds == other.periodSeconds

    override fun hashCode() = listOf(secret.contentHashCode(), issuer, account, algorithm, digits, periodSeconds).hashCode()

    companion object {
        private val POW10 = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** Parses `otpauth://totp/Issuer:account?secret=...`. HOTP (counter-based) is rejected. */
        fun parseUri(uri: String): TotpSpec {
            val u = try {
                URI(uri.trim())
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a valid 2FA QR code", e)
            }
            require(u.scheme.equals("otpauth", true)) { "Not a 2FA QR code" }
            require(u.host.equals("totp", true)) {
                if (u.host.equals("hotp", true)) "Counter-based (HOTP) codes aren't supported" else "Not a time-based 2FA code"
            }
            val params = (u.rawQuery ?: "").split('&').filter { '=' in it }.associate {
                it.substringBefore('=').lowercase() to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val label = URLDecoder.decode(u.rawPath.orEmpty().removePrefix("/"), "UTF-8")
            val labelIssuer = label.substringBefore(':', "").trim()
            val account = label.substringAfter(':').trim()
            val secret = params["secret"] ?: throw IllegalArgumentException("QR code has no secret")
            return TotpSpec(
                secret = Base32.decode(secret),
                issuer = params["issuer"]?.trim() ?: labelIssuer,
                account = account,
                algorithm = when (params["algorithm"]?.uppercase()) {
                    null, "SHA1" -> OtpAlgorithm.SHA1
                    "SHA256" -> OtpAlgorithm.SHA256
                    "SHA512" -> OtpAlgorithm.SHA512
                    else -> throw IllegalArgumentException("Unsupported algorithm ${params["algorithm"]}")
                },
                digits = params["digits"]?.toIntOrNull() ?: 6,
                periodSeconds = params["period"]?.toIntOrNull() ?: 30,
            )
        }

        /** Manual entry of a setup key (Base32) with optional settings. */
        fun fromKey(key: String, issuer: String = "", account: String = "", algorithm: OtpAlgorithm = OtpAlgorithm.SHA1, digits: Int = 6, period: Int = 30) =
            TotpSpec(Base32.decode(key), issuer, account, algorithm, digits, period)
    }
}
