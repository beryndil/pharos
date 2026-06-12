package com.beryndil.pharos.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Binary envelope header for Pharos encrypted backup files (Standards §6 LAUNCH-BLOCKER).
 *
 * The header is serialised to bytes that serve a dual purpose:
 *  1. Written as the first [HEADER_SIZE] bytes of the backup file.
 *  2. Passed as AES-GCM AAD so any modification to the header is detected before decryption.
 *
 * File layout:
 * ```
 * Offset  Len   Field
 *  0      13    magic = "PHAROS_BACKUP" (UTF-8)
 * 13       1    version = 0x01
 * 14       1    kdf_id  = 0x01 (Argon2id)
 * 15       4    argon2_t  (big-endian int = 3)
 * 19       4    argon2_m_kb (big-endian int = 65536)
 * 23       4    argon2_p  (big-endian int = 4)
 * 27       4    key_len   (big-endian int = 32)
 * 31      16    salt (random, 16 bytes)
 * 47      12    nonce (random, 12 bytes)
 * 59       8    content_len (big-endian long — plaintext byte count)
 * ─────   ─────────────────────────────────────────────
 * 67            [HEADER_SIZE]
 *
 * 67  content_len+16   AES-256-GCM ciphertext + 16-byte auth tag
 * ```
 */
data class BackupEnvelope(
    val version: Byte = CURRENT_VERSION,
    val kdfId: Byte = BackupCrypto.KDF_ARGON2ID,
    val argon2T: Int = BackupCrypto.ARGON2_T,
    val argon2MKb: Int = BackupCrypto.ARGON2_M_KB,
    val argon2P: Int = BackupCrypto.ARGON2_P,
    val keyLen: Int = BackupCrypto.KEY_LEN,
    /** 16-byte random salt — unique per backup file. */
    val salt: ByteArray,
    /** 12-byte random nonce — unique per backup file. */
    val nonce: ByteArray,
    /** Byte count of the plaintext (JSON payload). */
    val contentLen: Long,
) {
    /**
     * Serialise this header to its canonical [HEADER_SIZE]-byte representation.
     * This same byte sequence is written to the file AND passed as AES-GCM AAD.
     */
    fun toBytes(): ByteArray {
        val buf = ByteArrayOutputStream(HEADER_SIZE)
        val out = DataOutputStream(buf)
        out.write(MAGIC)                 // 13 bytes
        out.writeByte(version.toInt())   //  1 byte
        out.writeByte(kdfId.toInt())     //  1 byte
        out.writeInt(argon2T)            //  4 bytes
        out.writeInt(argon2MKb)          //  4 bytes
        out.writeInt(argon2P)            //  4 bytes
        out.writeInt(keyLen)             //  4 bytes
        out.write(salt)                  // 16 bytes
        out.write(nonce)                 // 12 bytes
        out.writeLong(contentLen)        //  8 bytes  → total 67
        out.flush()
        return buf.toByteArray()
    }

    companion object {
        /** "PHAROS_BACKUP" encoded as UTF-8 — 13 bytes. */
        val MAGIC: ByteArray = "PHAROS_BACKUP".toByteArray(Charsets.UTF_8)

        const val CURRENT_VERSION: Byte = 0x01

        /**
         * Total header byte count: 13+1+1+4+4+4+4+16+12+8 = 67.
         * Modifying this value is a breaking schema change.
         */
        const val HEADER_SIZE = 67

        /** Max supported plaintext size (256 MiB) — sanity guard against malformed inputs. */
        const val MAX_PLAINTEXT_LEN = 256L * 1024L * 1024L

        /**
         * Read and validate the envelope header from [input].
         *
         * Returns a pair of (parsed envelope, raw header bytes).
         * The raw bytes MUST be used as AES-GCM AAD during decryption (they are the
         * exact bytes read from disk; re-serialising would be equivalent but unnecessarily
         * complex and could mask a deserialization bug).
         *
         * @throws InvalidBackupException on any format or version mismatch.
         */
        fun readFrom(input: DataInputStream): Pair<BackupEnvelope, ByteArray> {
            // Read the raw header bytes first so we can use them verbatim as AAD.
            val raw = ByteArray(HEADER_SIZE)
            try {
                input.readFully(raw)
            } catch (e: Exception) {
                throw InvalidBackupException("File is too short to be a Pharos backup.", e)
            }

            val din = DataInputStream(raw.inputStream())

            val magicBytes = ByteArray(MAGIC.size)
            din.readFully(magicBytes)
            if (!magicBytes.contentEquals(MAGIC)) {
                throw InvalidBackupException("Not a Pharos backup file (wrong magic bytes).")
            }

            val version = din.readByte()
            if (version != CURRENT_VERSION) {
                throw InvalidBackupException(
                    "This backup was created with a newer version of the app (backup version $version). " +
                        "Update the app to restore it.",
                )
            }

            val kdfId = din.readByte()
            if (kdfId != BackupCrypto.KDF_ARGON2ID) {
                throw InvalidBackupException(
                    "Unrecognised KDF algorithm (id = $kdfId). This backup may have been created " +
                        "with a newer version of the app.",
                )
            }

            val t = din.readInt()
            val mKb = din.readInt()
            val p = din.readInt()
            val keyLen = din.readInt()

            val salt = ByteArray(BackupCrypto.SALT_LEN)
            din.readFully(salt)

            val nonce = ByteArray(BackupCrypto.NONCE_LEN)
            din.readFully(nonce)

            val contentLen = din.readLong()
            if (contentLen < 0 || contentLen > MAX_PLAINTEXT_LEN) {
                throw InvalidBackupException("Backup file is corrupt (invalid content length: $contentLen).")
            }

            val envelope = BackupEnvelope(
                version = version,
                kdfId = kdfId,
                argon2T = t,
                argon2MKb = mKb,
                argon2P = p,
                keyLen = keyLen,
                salt = salt,
                nonce = nonce,
                contentLen = contentLen,
            )
            return Pair(envelope, raw)
        }
    }
}

/**
 * Thrown when a backup file is structurally invalid, uses an unknown version/algorithm,
 * has a wrong passphrase (auth tag failure), or is otherwise unrestorable.
 *
 * The [message] is user-displayable (plain language, no jargon).
 */
class InvalidBackupException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
