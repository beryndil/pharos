package com.beryndil.pharos.backup

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives for backup encryption/decryption.
 *
 * Standards §6 LAUNCH-BLOCKER requirements met:
 *  - AES-256-GCM via JCE "AES/GCM/NoPadding" (not Tink — Tink is for DB-key wrapping only)
 *  - Argon2id KDF (m=64 MiB, t=3, p=4) via BouncyCastle 1.78 direct API
 *  - 16-byte random salt + 12-byte random nonce per file, never reused
 *  - 128-bit GCM authentication tag
 *  - Envelope header authenticated as AAD (reject tampered header before any plaintext)
 *  - Key and passphrase-derived bytes zeroed after use
 *
 * BouncyCastle usage: direct API (not registered as a JCE provider) to avoid the
 * platform-BC namespace conflict on API 26–30 (see DECISIONS.md S9-A1).
 */
object BackupCrypto {

    const val SALT_LEN = 16
    const val NONCE_LEN = 12
    const val KEY_LEN = 32       // AES-256
    const val TAG_LEN_BITS = 128 // GCM tag

    // KDF identifiers stored in the envelope header
    const val KDF_ARGON2ID: Byte = 0x01

    // Argon2id parameters (spec: m=64 MiB, t=3, p=4)
    const val ARGON2_T = 3
    const val ARGON2_M_KB = 64 * 1024 // 64 MiB in kilobytes
    const val ARGON2_P = 4

    private val secureRandom = SecureRandom()

    /** Returns 16 random bytes suitable for use as the backup salt. */
    fun generateSalt(): ByteArray = ByteArray(SALT_LEN).also { secureRandom.nextBytes(it) }

    /** Returns 12 random bytes suitable for use as the GCM nonce. */
    fun generateNonce(): ByteArray = ByteArray(NONCE_LEN).also { secureRandom.nextBytes(it) }

    /**
     * Derives a 32-byte AES key from [passphrase] and [salt] using Argon2id.
     *
     * Parameters: m=64 MiB, t=3, p=4 (Standards §6).
     * The caller MUST zero the returned array after use.
     * [passphrase] should also be zeroed by the caller after use.
     */
    fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val output = ByteArray(KEY_LEN)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withIterations(ARGON2_T)
            .withMemoryAsKB(ARGON2_M_KB)
            .withParallelism(ARGON2_P)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        gen.generateBytes(passphrase, output, 0, KEY_LEN)
        return output
    }

    /**
     * AES-256-GCM encrypt.
     *
     * Returns ciphertext + auth tag (total size = [plaintext].size + 16).
     * [aad] is authenticated but not encrypted (the envelope header bytes).
     * [key] is zeroed after use — caller must not reuse it.
     *
     * @throws javax.crypto.IllegalBlockSizeException on invalid key/input
     */
    fun encrypt(
        plaintext: ByteArray,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_LEN_BITS, nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(plaintext)
    } finally {
        key.fill(0)
    }

    /**
     * AES-256-GCM decrypt.
     *
     * [ciphertext] must be the GCM ciphertext including the 16-byte auth tag (as returned
     * by [encrypt]).
     * [key] is zeroed after use.
     *
     * @throws AEADBadTagException if the auth tag fails — data is corrupt, tampered, or the
     *   wrong passphrase was used. No plaintext is returned on failure.
     */
    fun decrypt(
        ciphertext: ByteArray,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_LEN_BITS, nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(ciphertext)
    } finally {
        key.fill(0)
    }
}
