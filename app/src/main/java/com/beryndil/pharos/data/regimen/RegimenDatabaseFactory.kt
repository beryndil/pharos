package com.beryndil.pharos.data.regimen

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
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
     * Current schema version. Used by Room's `@Database(version = ...)` annotation (set to 1)
     * AND by the newer-schema guard to detect on-disk databases from future app versions.
     * Keep in sync with [RegimenDatabase]'s `@Database` annotation.
     */
    const val CURRENT_VERSION = 1

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
