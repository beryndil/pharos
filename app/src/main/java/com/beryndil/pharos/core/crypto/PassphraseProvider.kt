package com.beryndil.pharos.core.crypto

import android.content.Context

/**
 * Abstraction for obtaining the SQLCipher passphrase for the regimen database.
 *
 * Production implementation ([TinkPassphraseProvider]) wraps a randomly-generated 32-byte key
 * with an Android Keystore AES-256-GCM key via Tink [AndroidKeysetManager].
 *
 * Test implementations (e.g., [FixedPassphraseProvider]) bypass the Keystore so that
 * Robolectric unit tests can open the database without Keystore access.
 *
 * IMPORTANT: The returned [ByteArray] must NOT be stored anywhere; zero it immediately after
 * passing to the [net.zetetic.database.sqlcipher.SupportFactory] constructor.
 */
interface PassphraseProvider {
    /** Returns a passphrase for the regimen DB. Never null; always [PASSPHRASE_LENGTH] bytes. */
    fun getOrCreatePassphrase(context: Context): ByteArray

    companion object {
        /** Required passphrase length in bytes (Standards §6: 32 random bytes). */
        const val PASSPHRASE_LENGTH = 32
    }
}

/**
 * Test-only passphrase provider that returns a fixed byte array without touching the Keystore.
 * Inject this when building an in-memory Room database in Robolectric tests.
 *
 * NEVER use in production — the passphrase is not secret.
 */
object FixedPassphraseProvider : PassphraseProvider {
    private val BYTES = ByteArray(PassphraseProvider.PASSPHRASE_LENGTH) { it.toByte() }

    override fun getOrCreatePassphrase(context: Context): ByteArray = BYTES.copyOf()
}
