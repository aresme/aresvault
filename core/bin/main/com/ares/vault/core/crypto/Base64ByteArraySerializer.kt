package com.ares.vault.core.crypto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

/** Serialises non-secret byte arrays (salts, IVs, ciphertexts) as Base64 strings. */
object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("Base64Bytes", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) =
        encoder.encodeString(Base64.getEncoder().encodeToString(value))

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.getDecoder().decode(decoder.decodeString())
}
