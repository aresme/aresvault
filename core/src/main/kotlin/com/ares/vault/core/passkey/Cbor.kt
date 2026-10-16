package com.ares.vault.core.passkey

import java.io.ByteArrayOutputStream

/**
 * Minimal deterministic CBOR encoder (RFC 8949) for the few structures WebAuthn needs: unsigned
 * and negative integers, byte strings, text strings and maps. Map keys are written in the
 * order given. Callers pass them in CTAP2 canonical order (shorter/lower first). Not crypto.
 */
object Cbor {
    sealed interface Value
    data class UInt(val v: Long) : Value
    data class NInt(val v: Long) : Value // v < 0
    class Bytes(val v: ByteArray) : Value
    data class Text(val v: String) : Value
    data class MapOf(val entries: List<Pair<Value, Value>>) : Value

    fun int(v: Long): Value = if (v >= 0) UInt(v) else NInt(v)
    fun int(v: Int): Value = int(v.toLong())

    fun encode(value: Value): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, value: Value) {
        when (value) {
            is UInt -> head(out, 0, value.v)
            is NInt -> head(out, 1, -1 - value.v)
            is Bytes -> { head(out, 2, value.v.size.toLong()); out.write(value.v) }
            is Text -> value.v.toByteArray().let { head(out, 3, it.size.toLong()); out.write(it) }
            is MapOf -> { head(out, 5, value.entries.size.toLong()); value.entries.forEach { (k, v) -> write(out, k); write(out, v) } }
        }
    }

    private fun head(out: ByteArrayOutputStream, major: Int, n: Long) {
        val m = major shl 5
        when {
            n < 24 -> out.write(m or n.toInt())
            n < 0x100 -> { out.write(m or 24); out.write(n.toInt()) }
            n < 0x10000 -> { out.write(m or 25); out.write((n shr 8).toInt()); out.write(n.toInt() and 0xFF) }
            n < 0x100000000L -> { out.write(m or 26); for (s in 24 downTo 0 step 8) out.write(((n shr s) and 0xFF).toInt()) }
            else -> { out.write(m or 27); for (s in 56 downTo 0 step 8) out.write(((n shr s) and 0xFF).toInt()) }
        }
    }
}
