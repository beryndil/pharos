package com.beryndil.pharos.core.crypto

import android.content.Context
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.SecureRandom

/**
 * Production [PassphraseProvider] that wraps the regimen-DB key using Tink [AndroidKeysetManager].
 *
 * Architecture (Standards §6):
 *  1. A 32-byte random raw key is generated on first use and encrypted ("wrapped") using a
 *     Tink AES-256-GCM keyset stored in [PREF_FILE] / [KEYSET_NAME].
 *  2. The Tink keyset itself is protected by an Android Keystore master key ([KEYSTORE_URI]),
 *     providing hardware-backed (TEE) envelope encryption.
 *  3. [setUserAuthenticationRequired] is intentionally NOT set — alarms must read the DB while
 *     the device is locked.
 *
 * StrongBox hardening (available on API 28+, `FEATURE_STRONGBOX_KEYSTORE`) is deferred to the
 * security hardening pass. The TEE-backed key is the default and is fully production-ready for
 * v1 launch. Tracked in DECISIONS.md.
 *
 * Thread-safety: first-call initialisation is synchronised; subsequent calls are lock-free.
 */
class TinkPassphraseProvider(private val context: Context) : PassphraseProvider {

    @Volatile
    private var cachedWrapped: ByteArray? = null

    override fun getOrCreatePassphrase(context: Context): ByteArray {
        // Return a freshly decrypted copy on every call so the caller can zero it independently.
        val aead = getOrCreateAead(context)
        val wrapped = getOrCreateWrappedKey(context, aead)
        return aead.decrypt(wrapped, ASSOCIATED_DATA)
    }

    // ── private helpers ────────────────────────────────────────────────────

    private fun getOrCreateAead(ctx: Context): Aead {
        AeadConfig.register()
        return AndroidKeysetManager.Builder()
            .withSharedPref(ctx, KEYSET_NAME, PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(KEYSTORE_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    @Synchronized
    private fun getOrCreateWrappedKey(ctx: Context, aead: Aead): ByteArray {
        cachedWrapped?.let { return it }

        val prefs = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val stored = prefs.getString(WRAPPED_KEY_PREF, null)
        if (stored != null) {
            val decoded = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
            cachedWrapped = decoded
            return decoded
        }

        // First use: generate 32 random bytes, wrap them, persist.
        val raw = ByteArray(PassphraseProvider.PASSPHRASE_LENGTH)
        SecureRandom().nextBytes(raw)
        val wrapped = aead.encrypt(raw, ASSOCIATED_DATA)
        raw.fill(0)

        prefs.edit()
            .putString(WRAPPED_KEY_PREF, android.util.Base64.encodeToString(wrapped, android.util.Base64.NO_WRAP))
            .apply()
        cachedWrapped = wrapped
        return wrapped
    }

    companion object {
        private const val TAG = "TinkPassphraseProvider"
        private const val PREF_FILE = "pharos_crypto_prefs"
        private const val KEYSET_NAME = "pharos_regimen_keyset"
        private const val WRAPPED_KEY_PREF = "pharos_regimen_wrapped_key"
        // android-keystore:// master key URI; TEE-backed on all supported API levels.
        private const val KEYSTORE_URI = "android-keystore://pharos_master_key"
        // AAD authenticates the wrapped-key ciphertext to prevent context-confusion attacks.
        private val ASSOCIATED_DATA = "pharos.regimen.db.key.v1".toByteArray(Charsets.UTF_8)
    }
}
