package com.ares.vault.core.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Reference Argon2id (Bouncy Castle) for JVM tests and cross-checking Argon2kt on device. */
class BouncyCastleArgon2Kdf : Kdf {
    override fun derive(password: ByteArray, salt: ByteArray, params: Argon2Params): ByteArray =
        derive(password, salt, params, secret = null, additional = null)

    fun derive(
        password: ByteArray,
        salt: ByteArray,
        params: Argon2Params,
        secret: ByteArray?,
        additional: ByteArray?,
    ): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
        secret?.let { builder.withSecret(it) }
        additional?.let { builder.withAdditional(it) }
        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        return ByteArray(params.outputLength).also { generator.generateBytes(password, it) }
    }
}
