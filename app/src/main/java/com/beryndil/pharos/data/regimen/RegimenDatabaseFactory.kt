package com.beryndil.pharos.data.regimen

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

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
    const val CURRENT_VERSION = 4

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
     * Builds [RegimenDatabase] with a newer-schema version guard.
     *
     * @param openHelperFactory SQLCipher [net.zetetic.database.sqlcipher.SupportFactory] in
     *   production; null in Robolectric tests (falls back to standard SQLite).
     * @throws NewerSchemaException if the on-disk database schema is newer than
     *   [CURRENT_VERSION] — caller should preserve the file and prompt for an app update.
     */
    fun build(
        context: Context,
        openHelperFactory: SupportSQLiteOpenHelper.Factory? = null,
    ): RegimenDatabase {
        enforceSchemaVersion(context)
        return Room.databaseBuilder(context, RegimenDatabase::class.java, DATABASE_NAME)
            .apply { if (openHelperFactory != null) openHelperFactory(openHelperFactory) }
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
    }

    /**
     * Reads the SQLite version from the on-disk file (bypassing Room) and throws
     * [NewerSchemaException] if it exceeds [CURRENT_VERSION]. Safe to call before
     * Room opens the file.
     */
    @Throws(NewerSchemaException::class)
    fun enforceSchemaVersion(context: Context) {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        if (!dbFile.exists()) return

        val storedVersion = try {
            SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { it.version }
        } catch (e: Exception) {
            // Unreadable file — let Room handle it.
            Log.w(TAG, "Cannot read DB version from $DATABASE_NAME; skipping guard.", e)
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
