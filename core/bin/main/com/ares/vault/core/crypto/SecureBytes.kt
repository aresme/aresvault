package com.ares.vault.core.crypto

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom

/** Small helpers for handling secret byte/char arrays. */
object SecureBytes {

    /** Returns [size] bytes from [random]; defaults to a fresh platform CSPRNG. */
    fun random(size: Int, random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(size).also { random.nextBytes(it) }

    fun wipe(vararg arrays: ByteArray?) {
        arrays.forEach { it?.fill(0) }
    }

    fun wipe(chars: CharArray?) {
        chars?.fill('\u0000')
    }

    /** Overwrites the remaining content of a (possibly direct) buffer with zeros. */
    fun wipe(buffer: ByteBuffer?) {
        if (buffer == null || buffer.isReadOnly) return
        buffer.clear()
        while (buffer.hasRemaining()) buffer.put(0)
        buffer.clear()
    }

    /**
     * Encodes [chars] as UTF-8 without creating an intermediate [String], so the
     * password never lands in the (immutable, un-wipeable) string pool.
     * Unpaired surrogates are rejected rather than silently replaced.
     */
    fun utf8(chars: CharArray): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = encoder.encode(CharBuffer.wrap(chars))
        return try {
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } finally {
            if (buffer.hasArray()) buffer.array().fill(0)
        }
    }

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { append(HEX[(it.toInt() shr 4) and 0xF]).append(HEX[it.toInt() and 0xF]) }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
