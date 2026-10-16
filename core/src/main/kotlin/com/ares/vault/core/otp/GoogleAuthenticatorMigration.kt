package com.ares.vault.core.otp

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * Decodes Google Authenticator's "Transfer accounts" QR codes:
 * `otpauth-migration://offline?data=<base64 protobuf MigrationPayload>`.
 *
 * Only the fields AresVault needs are read (proto field numbers from Google's published
 * schema): otp_parameters(1){secret(1) name(2) issuer(3) algorithm(4) digits(5) type(6)},
 * batch_size(3), batch_index(4). Unknown fields are skipped. This is a wire-format reader, not crypto.
 */
object GoogleAuthenticatorMigration {
    data class Batch(val accounts: List<TotpSpec>, val skippedHotp: Int, val batchIndex: Int, val batchSize: Int)

    fun parse(uri: String): Batch {
        val u = URI(uri.trim())
        require(u.scheme.equals("otpauth-migration", true)) { "Not a Google Authenticator export QR code" }
        val data = (u.rawQuery ?: "").split('&').firstOrNull { it.startsWith("data=") }?.substringAfter("data=")
            ?: throw IllegalArgumentException("Export QR code has no data")
        val bytes = Base64.getDecoder().decode(URLDecoder.decode(data, "UTF-8"))
        val accounts = mutableListOf<TotpSpec>()
        var skipped = 0
        var batchIndex = 0
        var batchSize = 1
        val r = Reader(bytes)
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 2 -> {
                    val p = Reader(r.bytes())
                    var secret = ByteArray(0)
                    var name = ""
                    var issuer = ""
                    var algorithm = 1
                    var digits = 1
                    var type = 2
                    while (p.hasMore()) {
                        val (f, w) = p.tag()
                        when {
                            f == 1 && w == 2 -> secret = p.bytes()
                            f == 2 && w == 2 -> name = p.bytes().decodeToString()
                            f == 3 && w == 2 -> issuer = p.bytes().decodeToString()
                            f == 4 && w == 0 -> algorithm = p.varint().toInt()
                            f == 5 && w == 0 -> digits = p.varint().toInt()
                            f == 6 && w == 0 -> type = p.varint().toInt()
                            else -> p.skip(w)
                        }
                    }
                    if (type == 1) {
                        skipped++
                        continue
                    }
                    val account = if (':' in name && issuer.isNotEmpty() && name.startsWith("$issuer:")) name.substringAfter(':').trim() else name
                    accounts += TotpSpec(
                        secret = secret,
                        issuer = issuer,
                        account = account,
                        algorithm = when (algorithm) {
                            2 -> OtpAlgorithm.SHA256
                            3 -> OtpAlgorithm.SHA512
                            else -> OtpAlgorithm.SHA1
                        },
                        digits = if (digits == 2) 8 else 6,
                    )
                }
                field == 3 && wire == 0 -> batchSize = r.varint().toInt()
                field == 4 && wire == 0 -> batchIndex = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        return Batch(accounts, skipped, batchIndex, batchSize)
    }

    /** Minimal protobuf wire-format reader. */
    private class Reader(private val buf: ByteArray) {
        private var pos = 0
        fun hasMore() = pos < buf.size
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < buf.size && shift < 64) { "Corrupt export data" }
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        fun tag(): Pair<Int, Int> = varint().let { (it ushr 3).toInt() to (it and 7).toInt() }
        fun bytes(): ByteArray {
            val len = varint().toInt()
            require(len >= 0 && pos + len <= buf.size) { "Corrupt export data" }
            return buf.copyOfRange(pos, pos + len).also { pos += len }
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> bytes()
                5 -> pos += 4
                else -> throw IllegalArgumentException("Corrupt export data")
            }
        }
    }
}
