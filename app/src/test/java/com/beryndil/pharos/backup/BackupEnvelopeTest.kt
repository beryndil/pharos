package com.beryndil.pharos.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.DataInputStream

/**
 * Unit tests for [BackupEnvelope] serialisation and parsing.
 */
@RunWith(JUnit4::class)
class BackupEnvelopeTest {

    @Test
    fun `envelope round-trips through toBytes and readFrom`() {
        val salt = BackupCrypto.generateSalt()
        val nonce = BackupCrypto.generateNonce()
        val contentLen = 1234L

        val original = BackupEnvelope(salt = salt, nonce = nonce, contentLen = contentLen)
        val bytes = original.toBytes()

        assertEquals(BackupEnvelope.HEADER_SIZE, bytes.size)

        val din = DataInputStream(bytes.inputStream())
        val (parsed, rawBytes) = BackupEnvelope.readFrom(din)

        assertEquals(original.version, parsed.version)
        assertEquals(original.kdfId, parsed.kdfId)
        assertEquals(original.argon2T, parsed.argon2T)
        assertEquals(original.argon2MKb, parsed.argon2MKb)
        assertEquals(original.argon2P, parsed.argon2P)
        assertEquals(original.keyLen, parsed.keyLen)
        assertArrayEquals(salt, parsed.salt)
        assertArrayEquals(nonce, parsed.nonce)
        assertEquals(contentLen, parsed.contentLen)

        // rawBytes must equal the serialised header so it can be used as AAD
        assertArrayEquals(bytes, rawBytes)
    }

    @Test
    fun `toBytes has correct size`() {
        val env = BackupEnvelope(
            salt = BackupCrypto.generateSalt(),
            nonce = BackupCrypto.generateNonce(),
            contentLen = 0,
        )
        assertEquals(BackupEnvelope.HEADER_SIZE, env.toBytes().size)
    }

    @Test(expected = InvalidBackupException::class)
    fun `readFrom throws on wrong magic`() {
        val bytes = ByteArray(BackupEnvelope.HEADER_SIZE)
        "NOT_A_BACKUP".toByteArray().copyInto(bytes)
        BackupEnvelope.readFrom(DataInputStream(bytes.inputStream()))
    }

    @Test(expected = InvalidBackupException::class)
    fun `readFrom throws on unknown version`() {
        val env = BackupEnvelope(
            salt = BackupCrypto.generateSalt(),
            nonce = BackupCrypto.generateNonce(),
            contentLen = 0,
        )
        val bytes = env.toBytes()
        bytes[13] = 0xFF.toByte() // overwrite version byte with unknown value
        BackupEnvelope.readFrom(DataInputStream(bytes.inputStream()))
    }

    @Test(expected = InvalidBackupException::class)
    fun `readFrom throws on too-short input`() {
        BackupEnvelope.readFrom(DataInputStream(ByteArray(5).inputStream()))
    }

    @Test(expected = InvalidBackupException::class)
    fun `readFrom throws on negative contentLen`() {
        val env = BackupEnvelope(
            salt = BackupCrypto.generateSalt(),
            nonce = BackupCrypto.generateNonce(),
            contentLen = 0,
        )
        val bytes = env.toBytes()
        // Write -1 as the 8-byte big-endian long at offset 59
        val negOneBytes = byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1)
        negOneBytes.copyInto(bytes, destinationOffset = 59)
        BackupEnvelope.readFrom(DataInputStream(bytes.inputStream()))
    }
}
