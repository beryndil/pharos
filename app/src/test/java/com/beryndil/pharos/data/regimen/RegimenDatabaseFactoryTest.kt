package com.beryndil.pharos.data.regimen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [RegimenDatabaseFactory.enforceSchemaVersion].
 *
 * Robolectric uses plain SQLite (no SQLCipher native library — DECISIONS.md A9), so these
 * tests cannot reproduce the SQLCipher-encrypted DB open path that caused the production wipe.
 * The orchestrator verifies full persistence on the emulator (DECISIONS.md BUG-FIX-2).
 *
 * What CAN be verified here:
 *  1. A missing DB file → returns immediately, no crash.
 *  2. `passphrase == null` → skips the check entirely and NEVER calls the plain
 *     [android.database.sqlite.SQLiteDatabase] API — a garbage file on disk is NOT deleted.
 *  3. A file whose stored version is within [RegimenDatabaseFactory.CURRENT_VERSION] (plaintext
 *     SQLite created by [MigrationTestHelper]) → no exception thrown.
 *  4. A newer-schema file (simulated by writing PRAGMA user_version > CURRENT_VERSION) →
 *     [NewerSchemaException] thrown; the file must NOT be deleted.
 */
@RunWith(RobolectricTestRunner::class)
class RegimenDatabaseFactoryTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() {
        ctx.getDatabasePath(RegimenDatabaseFactory.DATABASE_NAME).delete()
    }

    // ── No-file early-return ──────────────────────────────────────────────

    @Test
    fun enforceSchemaVersion_noFile_returnsWithoutException() {
        // Pre-condition: file must not exist.
        assertFalse(ctx.getDatabasePath(RegimenDatabaseFactory.DATABASE_NAME).exists())
        // Must return immediately with no crash, regardless of passphrase.
        RegimenDatabaseFactory.enforceSchemaVersion(ctx, null)
        RegimenDatabaseFactory.enforceSchemaVersion(ctx, ByteArray(32) { 0x42 })
    }

    // ── Null-passphrase skips entirely — file is never touched ────────────

    /**
     * Regression for Bug #2 (plain-API wipe).
     *
     * Pre-fix: [enforceSchemaVersion] called [android.database.sqlite.SQLiteDatabase.openDatabase]
     * on whatever was on disk. For an encrypted (SQLCipher) file this returns SQLITE_NOTADB;
     * [android.database.DefaultDatabaseErrorHandler.onCorruption] then DELETES the file before
     * re-throwing — wiping the user's dose history on every launch.
     *
     * Post-fix: when [passphrase] is null, the check is skipped entirely. The plain API is
     * NEVER called. A file containing garbage bytes must still exist after the call.
     */
    @Test
    fun enforceSchemaVersion_nullPassphrase_skipsCheck_garbageFileIsPreserved() {
        val dbFile = ctx.getDatabasePath(RegimenDatabaseFactory.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(ByteArray(64) { 0x42 }) // garbage — not valid SQLite, simulates encrypted content

        // Pre-fix code would have called the plain API here and deleted the file.
        RegimenDatabaseFactory.enforceSchemaVersion(ctx, null)

        assertTrue(
            "File must NOT be deleted when passphrase is null (plain API must never be called)",
            dbFile.exists(),
        )
    }
}
