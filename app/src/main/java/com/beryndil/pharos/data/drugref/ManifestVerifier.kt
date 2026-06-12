package com.beryndil.pharos.data.drugref

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException

/**
 * Verifies an Ed25519 signature over the raw bytes of the CDN manifest (spec §3.5, Standards §6).
 *
 * The embedded [APP_PUBLIC_KEY] is a 32-byte Ed25519 public key. Its matching private key lives
 * exclusively in Dave's CDN build job (out-of-tree). The embedded public key is NOT a secret.
 *
 * ── REPLACING THE KEY ─────────────────────────────────────────────────────────────────────────
 * Current key: development/fixture keypair. Replace [APP_PUBLIC_KEY_HEX] with your real CDN key
 * before releasing to production. See DECISIONS.md §S8-A3 for the keypair generation procedure.
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * Signature format: raw 64-byte Ed25519 signature stored at `{cdnBaseUrl}/pharos_drug_ref_manifest.json.sig`.
 * The signature is computed over the exact UTF-8 bytes of the manifest JSON file.
 *
 * @param publicKeyBytes 32-byte raw Ed25519 public key. Tests inject a fresh key; the app
 *   uses [APP_PUBLIC_KEY].
 */
class ManifestVerifier(private val publicKeyBytes: ByteArray) {

    init {
        require(publicKeyBytes.size == PUBLIC_KEY_SIZE) {
            "Ed25519 public key must be $PUBLIC_KEY_SIZE bytes, got ${publicKeyBytes.size}"
        }
    }

    /**
     * Returns true iff [signature] is a valid Ed25519 signature over [manifestBytes] for the
     * configured public key.
     *
     * Signature must be exactly 64 bytes. Any verification failure (tampered data, wrong key,
     * wrong signature, malformed inputs) returns false — never throws.
     */
    fun verify(manifestBytes: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != SIGNATURE_SIZE) return false
        return try {
            Ed25519Verify(publicKeyBytes).verify(signature, manifestBytes)
            true
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    companion object {
        const val PUBLIC_KEY_SIZE = 32
        const val SIGNATURE_SIZE = 64

        /**
         * Manifest format version this verifier understands. The app refuses a manifest whose
         * [CdnManifest.schemaVersion] exceeds this value (forward-compat guard).
         */
        const val SUPPORTED_MANIFEST_SCHEMA_VERSION = 1

        /**
         * Development / fixture Ed25519 public key (RFC 8032 §6 test vector).
         * REPLACE THIS with your real CDN key before production. See DECISIONS.md S8-A3.
         *
         * Private key (seed, 32 bytes): 9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae3d55
         * Keep the private key in your CDN build job only — never commit it.
         */
        const val APP_PUBLIC_KEY_HEX =
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"

        /** Decoded bytes of [APP_PUBLIC_KEY_HEX]. Used by [DrugDbUpdater] in production. */
        val APP_PUBLIC_KEY: ByteArray by lazy { hexToBytes(APP_PUBLIC_KEY_HEX) }

        /** Convenience factory using the embedded [APP_PUBLIC_KEY]. */
        fun production(): ManifestVerifier = ManifestVerifier(APP_PUBLIC_KEY)

        internal fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "Hex string must have even length" }
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
