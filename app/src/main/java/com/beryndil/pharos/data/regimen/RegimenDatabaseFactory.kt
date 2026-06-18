package com.beryndil.pharos.data.regimen

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook

/**
 * Factory and newer-schema guard for [RegimenDatabase].
 *
 * Separated from the [RegimenDatabase] `@Database` class so that Room's KSP annotation
 * processor only sees the clean DAO/entity declaration, not the SQLCipher factory types.
 */
object RegimenDatabaseFactory {

    const val DATABASE_NAME = "pharos_regimen.db"

    /**
     * Current schema version. Used by Room's `@Database(version = ...)` annotation
     * AND by the newer-schema guard to detect on-disk databases from future app versions.
     * Keep in sync with [RegimenDatabase]'s `@Database` annotation.
     */
    const val CURRENT_VERSION = 13

    /**
     * v1 → v2 (Slice 5): adds the append-only [dose_transitions] history table. Additive only —
     * no existing column is touched, so dose history is preserved (Standards §5: never a
     * destructive migration on a path users reach).
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `dose_transitions` (" +
                    "`id` TEXT NOT NULL, " +
                    "`doseInstanceId` TEXT NOT NULL, " +
                    "`medicationId` TEXT NOT NULL, " +
                    "`fromState` TEXT, " +
                    "`toState` TEXT NOT NULL, " +
                    "`cause` TEXT NOT NULL, " +
                    "`atEpochMs` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`doseInstanceId`) REFERENCES `dose_instances`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                    "FOREIGN KEY(`medicationId`) REFERENCES `medications`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE RESTRICT)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dose_transitions_doseInstanceId` " +
                    "ON `dose_transitions` (`doseInstanceId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dose_transitions_medicationId` " +
                    "ON `dose_transitions` (`medicationId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dose_transitions_atEpochMs` " +
                    "ON `dose_transitions` (`atEpochMs`)",
            )
        }
    }

    /**
     * v2 → v3 (A1 Critical Alerts): adds `isCritical` boolean column to [medications].
     * Additive only — no existing column is touched. All existing rows default to 0 (false).
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `medications` ADD COLUMN `isCritical` INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    /**
     * v3 → v4 (G1 Per-medication miss window): adds [missWindowMinutes] column to [medications].
     * Additive only — no existing column is touched. All existing rows default to 60 (the prior
     * hardcoded value), so no behavioral change for pre-existing medications (Standards §5).
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `medications` ADD COLUMN `missWindowMinutes` INTEGER NOT NULL DEFAULT 60",
            )
        }
    }

    /**
     * v4 \u2192 v5 (V1.3-F1 Saved contacts): creates [prescribers] and [pharmacies] tables and
     * adds [prescriberPhone] / [pharmacyPhone] columns to [medications]. Additive only — no
     * existing data is touched. Phone columns default to NULL (contacts that had no phone keep no
     * phone). Standards \u00a75: never destructive; data is preserved.
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `prescribers` (" +
                    "`id` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`phone` TEXT, " +
                    "`createdAtEpochMs` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `pharmacies` (" +
                    "`id` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`phone` TEXT, " +
                    "`createdAtEpochMs` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL("ALTER TABLE `medications` ADD COLUMN `prescriberPhone` TEXT")
            db.execSQL("ALTER TABLE `medications` ADD COLUMN `pharmacyPhone` TEXT")
        }
    }

    /**
     * v5 → v6 (V1.3-F2 Drug substitution link): adds [substituteForMedId] and [substituteNote]
     * nullable TEXT columns to [medications]. Additive only — no existing column is touched.
     * Both columns default to NULL so pre-existing medications have no substitution link,
     * preserving all prior behavior (Standards §5: never destructive).
     */
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `medications` ADD COLUMN `substituteForMedId` TEXT")
            db.execSQL("ALTER TABLE `medications` ADD COLUMN `substituteNote` TEXT")
        }
    }

    /**
     * v6 → v7 (prescriber practice field): adds [practice] to [prescribers] and
     * [prescriberPractice] to [medications]. Additive only — no existing column is touched.
     * Both default to NULL so pre-existing contacts and medications are unchanged.
     */
    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `prescribers` ADD COLUMN `practice` TEXT")
            db.execSQL("ALTER TABLE `medications` ADD COLUMN `prescriberPractice` TEXT")
        }
    }

    /**
     * v7 → v8: adds [notes] nullable TEXT column to [medications]. Additive only —
     * no existing column is touched. Defaults to NULL so pre-existing medications have no note.
     */
    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `medications` ADD COLUMN `notes` TEXT")
        }
    }

    /**
     * v8 → v9: adds [substituteForDrugName] nullable TEXT column to [medications].
     *
     * Replaces the old FK-style [substituteForMedId] (which pointed to another medication in the
     * regimen) with a free-text drug name searched from the drug reference DB. This lets the user
     * record "tamsulosin substituted for Flomax" even when Flomax is not in their regimen.
     * [substituteForMedId] is kept as a dead column to avoid a table-recreation migration.
     */
    val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `medications` ADD COLUMN `substituteForDrugName` TEXT")
        }
    }

    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `medications` ADD COLUMN `combinedWithMedId` TEXT")
            db.execSQL("ALTER TABLE `medications` ADD COLUMN `combinedDisplayStrength` TEXT")
        }
    }

    val MIGRATION_10_11: Migration = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `schedules` ADD COLUMN `indication` TEXT")
        }
    }

    /**
     * v11 → v12: deduplicates dose_instances and adds a UNIQUE index on (scheduleId, dueEpochMs).
     *
     * Root cause: topUpGeneration() had a TOCTOU race — getDueTimesForSchedule() and the subsequent
     * insert were not atomic. Two concurrent invocations (startup + onReRegistration) both read an
     * empty set and both inserted the same instances, creating duplicate rows with different UUIDs
     * but identical scheduleId + dueEpochMs.
     *
     * The dedup step keeps the row with the lowest rowid (oldest insert) per pair. Since duplicates
     * were always created atomically by the same race, both copies will be in the same state —
     * MIN(rowid) is safe and avoids complex multi-column priority logic.
     *
     * The unique index prevents future races from inserting duplicates (insertAll uses IGNORE
     * conflict strategy, so a racing second insert silently skips the duplicate row).
     */
    val MIGRATION_11_12: Migration = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "DELETE FROM dose_instances WHERE rowid NOT IN (" +
                    "SELECT MIN(rowid) FROM dose_instances GROUP BY scheduleId, dueEpochMs" +
                    ")",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_dose_instances_scheduleId_dueEpochMs` " +
                    "ON `dose_instances` (`scheduleId`, `dueEpochMs`)",
            )
        }
    }

    /** v12 → v13: adds [weekInterval] to [schedules] for every-N-weeks DAYS_OF_WEEK schedules. */
    val MIGRATION_12_13: Migration = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `schedules` ADD COLUMN `weekInterval` INTEGER NOT NULL DEFAULT 1")
        }
    }

    /**
     * Builds [RegimenDatabase] with a newer-schema version guard.
     *
     * @param openHelperFactory SQLCipher [net.zetetic.database.sqlcipher.SupportFactory] in
     *   production; null in Robolectric tests (falls back to standard SQLite).
     * @param passphrase Raw passphrase bytes for the SQLCipher DB. When non-null,
     *   [enforceSchemaVersion] opens the file via the SQLCipher API to read the version.
     *   When null (Robolectric tests, no encryption), the version check is skipped entirely
     *   so the plain [android.database.sqlite.SQLiteDatabase] API is never called on an
     *   encrypted file — which would trigger [android.database.DefaultDatabaseErrorHandler]
     *   and delete the file.
     * @throws NewerSchemaException if the on-disk database schema is newer than
     *   [CURRENT_VERSION] — caller should preserve the file and prompt for an app update.
     */
    fun build(
        context: Context,
        openHelperFactory: SupportSQLiteOpenHelper.Factory? = null,
        passphrase: ByteArray? = null,
    ): RegimenDatabase {
        enforceSchemaVersion(context, passphrase)
        return Room.databaseBuilder(context, RegimenDatabase::class.java, DATABASE_NAME)
            .apply { if (openHelperFactory != null) openHelperFactory(openHelperFactory) }
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
            .build()
    }

    /**
     * Reads the schema version from the on-disk file via the SQLCipher API and throws
     * [NewerSchemaException] if it exceeds [CURRENT_VERSION].
     *
     * **Why SQLCipher only, never the plain API:**
     * The regimen DB is SQLCipher-encrypted. Opening it with the plain
     * [android.database.sqlite.SQLiteDatabase] API returns SQLITE_NOTADB, which causes
     * [android.database.DefaultDatabaseErrorHandler.onCorruption] to DELETE the file before
     * re-throwing — wiping the user's entire dose history on every launch (device-confirmed bug).
     *
     * When [passphrase] is null (Robolectric tests — plain SQLite, no native SQLCipher lib),
     * the check is skipped entirely. In-memory test DBs never exist on disk so the early
     * [!dbFile.exists()] guard handles them; file-backed test DBs should not be encrypted.
     *
     * @param passphrase SQLCipher passphrase; null → skip (Robolectric / no-encryption path).
     * @throws NewerSchemaException if the stored version exceeds [CURRENT_VERSION].
     */
    @Throws(NewerSchemaException::class)
    fun enforceSchemaVersion(context: Context, passphrase: ByteArray? = null) {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        if (!dbFile.exists()) return

        // When no passphrase is supplied (tests), skip — never fall back to the plain API.
        // The plain API cannot open an encrypted file; falling back would invoke
        // DefaultDatabaseErrorHandler.onCorruption() and delete the file.
        if (passphrase == null) return

        val storedVersion = try {
            // openDatabase(path, byte[], CursorFactory, flags, SQLiteDatabaseHook)
            // The SQLiteDatabaseHook is nullable — pass null (no pre/post-key hook needed).
            CipherSQLiteDatabase.openDatabase(
                dbFile.path,
                passphrase,
                null,
                CipherSQLiteDatabase.OPEN_READONLY,
                null as SQLiteDatabaseHook?,
            ).use { db ->
                db.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }
        } catch (e: Exception) {
            // Unexpected open failure — log and let Room handle it.
            // Do NOT delete the file: it may hold the user's only copy of their dose history.
            Log.w(TAG, "Cannot read DB version from $DATABASE_NAME via SQLCipher; skipping guard.", e)
            return
        }

        if (storedVersion > CURRENT_VERSION) {
            throw NewerSchemaException(storedVersion, CURRENT_VERSION)
        }
    }

    private const val TAG = "RegimenDatabaseFactory"
}

/**
 * Thrown when the on-disk regimen DB schema version exceeds the version this build understands.
 * The caller must NOT delete the file — it may be the user's only copy of their dose history.
 */
class NewerSchemaException(
    val storedVersion: Int,
    val supportedVersion: Int,
) : IllegalStateException(
    "RegimenDatabase schema v$storedVersion is newer than supported v$supportedVersion. " +
        "Update the app to open this database.",
)
