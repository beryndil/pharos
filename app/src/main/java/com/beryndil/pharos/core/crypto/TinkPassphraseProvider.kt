package com.beryndil.pharos.core.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator

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

        // Attempt StrongBox-backed wrapping key on API 28+ (Standards §6, A3-6).
        // Pre-create the Android Keystore key with setIsStrongBoxBacked(true) before Tink
        // constructs its AndroidKeysetManager; Tink finds the pre-created key and uses it.
        // If StrongBox is absent the exception is caught and Tink creates a TEE-backed key.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tryProvisionStrongBoxKey(KEYSTORE_ALIAS)
        }

        return AndroidKeysetManager.Builder()
            .withSharedPref(ctx, KEYSET_NAME, PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(KEYSTORE_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    /**
     * Pre-creates the Android Keystore wrapping key with StrongBox backing (API 28+).
     * If the key already exists (including one created without StrongBox on a prior app launch),
     * this is a no-op — we do not replace an existing key to avoid losing the wrapped keyset.
     * Catches [StrongBoxUnavailableException] and falls through silently; Tink will then create
     * a standard TEE-backed key via its own `withMasterKeyUri` logic (A3-6).
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun tryProvisionStrongBoxKey(alias: String) {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            if (ks.containsAlias(alias)) return  // Already provisioned; do not replace.

            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(true)
                .build()

            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(spec)
            kg.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            // Device has no StrongBox; Tink will fall back to the TEE-backed key.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Log.d(TAG, "StrongBox unavailable on this device; using TEE-backed wrapping key")
            }
        } catch (e: Exception) {
            // Any other provisioning failure (e.g. keystore initialisation error) is logged
            // and swallowed — Tink creates its own key which is production-ready for v1.
            Log.w(TAG, "StrongBox provisioning failed; falling back to Tink default", e)
        }
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
        // The Android Keystore alias for the Tink master (wrapping) key.
        private const val KEYSTORE_ALIAS = "pharos_master_key"
        // android-keystore:// master key URI consumed by AndroidKeysetManager.
        private const val KEYSTORE_URI = "android-keystore://$KEYSTORE_ALIAS"
        // AAD authenticates the wrapped-key ciphertext to prevent context-confusion attacks.
        private val ASSOCIATED_DATA = "pharos.regimen.db.key.v1".toByteArray(Charsets.UTF_8)
    }
}
