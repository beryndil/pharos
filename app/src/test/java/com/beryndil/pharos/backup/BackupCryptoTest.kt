package com.beryndil.pharos.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import javax.crypto.AEADBadTagException

/**
 * Unit tests for [BackupCrypto].
 *
 * These tests run on the JVM (no Android deps). BouncyCastle and JCE are available in the
 * JVM test environment without emulator.
 */
@RunWith(JUnit4::class)
class BackupCryptoTest {

    // ── Argon2id KDF ──────────────────────────────────────────────────────────

    @Test
    fun `Argon2id is deterministic for same passphrase and salt`() {
        val passphrase = "correct-horse-battery".toCharArray()
        val salt = BackupCrypto.generateSalt()

        val key1 = BackupCrypto.deriveKey(passphrase.copyOf(), salt)
        val key2 = BackupCrypto.deriveKey(passphrase.copyOf(), salt)

        assertArrayEquals("Same passphrase+salt must produce the same key", key1, key2)
    }

    @Test
    fun `Argon2id produces different keys for different salts`() {
        val passphrase = "correct-horse-battery".toCharArray()
        val salt1 = BackupCrypto.generateSalt()
        val salt2 = BackupCrypto.generateSalt()

        val key1 = BackupCrypto.deriveKey(passphrase.copyOf(), salt1)
        val key2 = BackupCrypto.deriveKey(passphrase.copyOf(), salt2)

        assertFalse("Different salts must produce different keys", key1.contentEquals(key2))
    }

    @Test
    fun `Argon2id produces different keys for different passphrases`() {
        val salt = BackupCrypto.generateSalt()

        val key1 = BackupCrypto.deriveKey("passphrase-a".toCharArray(), salt)
        val key2 = BackupCrypto.deriveKey("passphrase-b".toCharArray(), salt)

        assertFalse("Different passphrases must produce different keys", key1.contentEquals(key2))
    }

    @Test
    fun `Argon2id key is KEY_LEN bytes`() {
        val key = BackupCrypto.deriveKey("test".toCharArray(), BackupCrypto.generateSalt())
        assert(key.size == BackupCrypto.KEY_LEN) { "Key must be ${BackupCrypto.KEY_LEN} bytes" }
    }

    // ── AES-256-GCM encrypt/decrypt ────────────────────────────────────────────

    @Test
    fun `encrypt then decrypt returns original plaintext`() {
        val passphrase = "test-passphrase".toCharArray()
        val salt = BackupCrypto.generateSalt()
        val nonce = BackupCrypto.generateNonce()
        val aad = "header-bytes".toByteArray()
        val plaintext = "Hello, Pharos!".toByteArray(Charsets.UTF_8)

        val key1 = BackupCrypto.deriveKey(passphrase.copyOf(), salt)
        val ciphertext = BackupCrypto.encrypt(plaintext, key1, nonce, aad)

        val key2 = BackupCrypto.deriveKey(passphrase.copyOf(), salt)
        val decrypted = BackupCrypto.decrypt(ciphertext, key2, nonce, aad)

        assertArrayEquals("Decrypted bytes must match original plaintext", plaintext, decrypted)
    }

    @Test(expected = AEADBadTagException::class)
    fun `decrypt with wrong passphrase throws AEADBadTagException`() {
        val salt = BackupCrypto.generateSalt()
        val nonce = BackupCrypto.generateNonce()
        val aad = "header".toByteArray()
        val plaintext = "sensitive data".toByteArray()

        val rightKey = BackupCrypto.deriveKey("correct".toCharArray(), salt)
        val ciphertext = BackupCrypto.encrypt(plaintext, rightKey, nonce, aad)

        val wrongKey = BackupCrypto.deriveKey("wrong".toCharArray(), salt)
        BackupCrypto.decrypt(ciphertext, wrongKey, nonce, aad) // must throw
    }

    @Test(expected = AEADBadTagException::class)
    fun `decrypt with tampered ciphertext throws AEADBadTagException`() {
        val passphrase = "passphrase".toCharArray()
        val salt = BackupCrypto.generateSalt()
        val nonce = BackupCrypto.generateNonce()
        val aad = "header".toByteArray()
        val plaintext = "sensitive".toByteArray()

        val key1 = BackupCrypto.deriveKey(passphrase.copyOf(), salt)
        val ciphertext = BackupCrypto.encrypt(plaintext, key1, nonce, aad).also {
            it[0] = (it[0].toInt() xor 0xFF).toByte() // flip a bit in the ciphertext
        }

        val key2 = BackupCrypto.deriveKey(passphrase.copyOf(), salt)
        BackupCrypto.decrypt(ciphertext, key2, nonce, aad) // must throw
    }

    @Test(expected = AEADBadTagException::class)
    fun `decrypt with tampered AAD throws AEADBadTagException`() {
        val passphrase = "passphrase".toCharArray()
        val salt = BackupCrypto.generateSalt()
        val nonce = BackupCrypto.generateNonce()
        val originalAad = "original-header".toByteArray()
        val tamperedAad = "tampered-header".toByteArray()
        val plaintext = "sensitive".toByteArray()

        val key1 = BackupCrypto.deriveKey(passphrase.copyOf(), salt)
        val ciphertext = BackupCrypto.encrypt(plaintext, key1, nonce, originalAad)

        val key2 = BackupCrypto.deriveKey(passphrase.copyOf(), salt)
        BackupCrypto.decrypt(ciphertext, key2, nonce, tamperedAad) // must throw
    }

    @Test
    fun `ciphertext is longer than plaintext by GCM tag length`() {
        val key = BackupCrypto.deriveKey("pw".toCharArray(), BackupCrypto.generateSalt())
        val nonce = BackupCrypto.generateNonce()
        val plaintext = ByteArray(100)
        val ciphertext = BackupCrypto.encrypt(plaintext, key, nonce, ByteArray(0))

        assert(ciphertext.size == plaintext.size + 16) {
            "GCM ciphertext must be plaintext+16 bytes, got ${ciphertext.size}"
        }
    }

    @Test
    fun `generateSalt returns SALT_LEN bytes`() {
        val salt = BackupCrypto.generateSalt()
        assert(salt.size == BackupCrypto.SALT_LEN)
    }

    @Test
    fun `generateNonce returns NONCE_LEN bytes`() {
        val nonce = BackupCrypto.generateNonce()
        assert(nonce.size == BackupCrypto.NONCE_LEN)
    }

    @Test
    fun `two generated salts are not equal`() {
        val s1 = BackupCrypto.generateSalt()
        val s2 = BackupCrypto.generateSalt()
        assertFalse("Two random salts should almost certainly differ", s1.contentEquals(s2))
    }
}
