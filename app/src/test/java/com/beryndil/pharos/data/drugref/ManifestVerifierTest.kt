package com.beryndil.pharos.data.drugref

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ManifestVerifier] Ed25519 signature verification (spec §3.5, Standards §6).
 *
 * Key invariants:
 *  - Valid signature over unchanged manifest bytes → accepted.
 *  - Any byte altered in the manifest → rejected (no swap).
 *  - Signature bytes altered → rejected.
 *  - Wrong public key → rejected.
 *  - Malformed signature (wrong length) → rejected.
 *
 * Uses a freshly-generated test keypair (via Tink) — never hits the real CDN.
 */
class ManifestVerifierTest {

    private val keyPair = Ed25519Sign.KeyPair.newKeyPair()
    private val signer = Ed25519Sign(keyPair.privateKey)
    private val verifier = ManifestVerifier(keyPair.publicKey)

    private val manifestBytes = """
        {"schema_version":1,"db_filename":"pharos_drug_ref.db","db_schema_version":1,"sha256_hex":"abc123","size_bytes":1024}
    """.trimIndent().toByteArray(Charsets.UTF_8)

    // ── Accept valid signatures ───────────────────────────────────────────────

    @Test
    fun validSignature_accepted() {
        val sig = signer.sign(manifestBytes)
        assertTrue("Valid Ed25519 signature must be accepted", verifier.verify(manifestBytes, sig))
    }

    @Test
    fun validSignature_acceptedForEmptyManifest() {
        val emptyBytes = "{}".toByteArray()
        val sig = signer.sign(emptyBytes)
        assertTrue(verifier.verify(emptyBytes, sig))
    }

    // ── Reject tampered data ──────────────────────────────────────────────────

    @Test
    fun tamperedManifest_rejected() {
        val sig = signer.sign(manifestBytes)
        val tampered = manifestBytes.clone().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        assertFalse("Tampered manifest must be rejected", verifier.verify(tampered, sig))
    }

    @Test
    fun oneByteChanged_atEnd_rejected() {
        val sig = signer.sign(manifestBytes)
        val tampered = manifestBytes.clone()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertFalse(verifier.verify(tampered, sig))
    }

    @Test
    fun appendedByte_rejected() {
        val sig = signer.sign(manifestBytes)
        val tampered = manifestBytes + byteArrayOf(0x00)
        assertFalse(verifier.verify(tampered, sig))
    }

    // ── Reject tampered signature ─────────────────────────────────────────────

    @Test
    fun tamperedSignature_rejected() {
        val sig = signer.sign(manifestBytes).clone().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        assertFalse("Tampered signature must be rejected", verifier.verify(manifestBytes, sig))
    }

    @Test
    fun allZeroSignature_rejected() {
        val zeroSig = ByteArray(ManifestVerifier.SIGNATURE_SIZE)
        assertFalse(verifier.verify(manifestBytes, zeroSig))
    }

    // ── Reject wrong key ──────────────────────────────────────────────────────

    @Test
    fun wrongPublicKey_rejected() {
        val sig = signer.sign(manifestBytes)
        val otherKey = Ed25519Sign.KeyPair.newKeyPair().publicKey
        val wrongVerifier = ManifestVerifier(otherKey)
        assertFalse("Signature verified with wrong public key must be rejected", wrongVerifier.verify(manifestBytes, sig))
    }

    // ── Reject malformed signatures ───────────────────────────────────────────

    @Test
    fun shortSignature_rejected() {
        val shortSig = ByteArray(32) // half the required size
        assertFalse(verifier.verify(manifestBytes, shortSig))
    }

    @Test
    fun longSignature_rejected() {
        val longSig = ByteArray(128)
        assertFalse(verifier.verify(manifestBytes, longSig))
    }

    @Test
    fun emptySignature_rejected() {
        assertFalse(verifier.verify(manifestBytes, ByteArray(0)))
    }

    // ── Companion helpers ─────────────────────────────────────────────────────

    @Test
    fun hexToBytes_roundTrips() {
        val hex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        val bytes = ManifestVerifier.hexToBytes(hex)
        val roundTripped = bytes.joinToString("") { "%02x".format(it) }
        assert(hex == roundTripped)
    }

    @Test
    fun appPublicKey_is32Bytes() {
        assert(ManifestVerifier.APP_PUBLIC_KEY.size == ManifestVerifier.PUBLIC_KEY_SIZE)
    }

    @Test
    fun production_factory_constructs() {
        // Should not throw with the embedded key.
        ManifestVerifier.production()
    }
}
