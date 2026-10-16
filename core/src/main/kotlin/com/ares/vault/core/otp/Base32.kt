package com.ares.vault.core.otp

/** RFC 4648 Base32 (the encoding of 2FA setup keys). Tolerates spaces, dashes, case and missing padding. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(input: String): ByteArray {
        val clean = input.uppercase().filterNot { it == ' ' || it == '-' || it == '=' }
        require(clean.isNotEmpty()) { "Empty key" }
        val out = java.io.ByteArrayOutputStream(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "Invalid character '$c' in key" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun encode(bytes: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                append(ALPHABET[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
    }
}
