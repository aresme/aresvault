package com.ares.vault.core.crypto

/**
 * Formats the 32-byte DEK as a SQLCipher raw key literal `x'<64 hex>'`.
 * Raw-key mode skips SQLCipher's internal PBKDF2: the DEK is already uniformly
 * random, so stretching it would only cost unlock time.
 * Built byte-by-byte so no String copy of the key is created.
 */
object SqlCipherKey {
    private val HEX = "0123456789abcdef".toByteArray(Charsets.US_ASCII)

    fun rawKey(dek: ByteArray): ByteArray {
        require(dek.size == AesGcm.KEY_BYTES) { "DEK must be 32 bytes" }
        val out = ByteArray(3 + dek.size * 2)
        out[0] = 'x'.code.toByte()
        out[1] = '\''.code.toByte()
        dek.forEachIndexed { i, b ->
            out[2 + i * 2] = HEX[(b.toInt() shr 4) and 0xF]
            out[3 + i * 2] = HEX[b.toInt() and 0xF]
        }
        out[out.size - 1] = '\''.code.toByte()
        return out
    }
}
