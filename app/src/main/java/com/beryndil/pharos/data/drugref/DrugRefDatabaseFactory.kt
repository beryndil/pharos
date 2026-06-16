package com.beryndil.pharos.data.drugref

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Factory and newer-schema guard for [DrugRefDatabase].
 *
 * Separated from the [DrugRefDatabase] `@Database` class so Room's KSP annotation processor
 * only sees the clean DAO/entity declaration.
 */
object DrugRefDatabaseFactory {

    const val DATABASE_NAME = "pharos_drug_ref.db"

    /**
     * Current schema version. Keep in sync with [DrugRefDatabase]'s `@Database` annotation.
     * Version 2 aligns to the RxNorm pipeline schema (drug_search / ingredient_map / db_meta).
     * CDN manifest guard: the app refuses to swap a CDN DB whose `db_schema_version` > this value.
     */
    const val CURRENT_VERSION = 4

    /**
     * Builds [DrugRefDatabase], running the newer-schema guard before Room opens the file.
     *
     * If the on-disk file has a schema version > [CURRENT_VERSION], the file is deleted
     * (it came from a future CDN push that this app version cannot interpret) and Room
     * will recreate from the bundled asset via the seeding callback.
     *
     * [fallbackToDestructiveMigration] is used for this plaintext, replaceable, public-data DB:
     * an old-schema file (e.g., v1 on first upgrade from the pre-pipeline build) is wiped and
     * reseeded from the bundled asset rather than attempting a migration.
     */
    /**
     * Migration 3→4: adds the nullable [foodEffectText] column to [label_cache].
     * [drug_search], [ingredient_map], and [db_meta] are unchanged — no re-seeding needed.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE label_cache ADD COLUMN foodEffectText TEXT")
        }
    }

    fun build(context: Context): DrugRefDatabase {
        handleNewerSchema(context)
        handleEmptyDatabase(context)
        return Room.databaseBuilder(context, DrugRefDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .addCallback(SeedCallback(context))
            .build()
    }

    /**
     * Deletes the DB file if it exists but the drug_search table is empty.
     *
     * This recovers devices where a prior destructive migration wiped the schema but did not
     * re-seed (because [SeedCallback.onCreate] is not called on [fallbackToDestructiveMigration]
     * paths — only [SeedCallback.onDestructiveMigration] is). Deleting here forces Room to call
     * [SeedCallback.onCreate] on next open.
     */
    private fun handleEmptyDatabase(context: Context) {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        if (!dbFile.exists()) return
        try {
            val empty = SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM drug_search", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) == 0
                }
            }
            if (empty) {
                Log.w(TAG, "DrugRef DB is empty (seeding missed on prior migration). Deleting to force reseed.")
                dbFile.delete()
            }
        } catch (_: Exception) {
            // Unreadable or missing table — let Room handle it.
        }
    }

    /**
     * Checks the on-disk DB version. If it is newer than [CURRENT_VERSION], deletes the file
     * so Room recreates from the bundled asset. Unlike [RegimenDatabaseFactory], drug-ref data
     * is replaceable (it is public reference data, not the user's health history).
     */
    fun handleNewerSchema(context: Context) {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        if (!dbFile.exists()) return
        val storedVersion = try {
            SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { it.version }
        } catch (e: Exception) {
            return // unreadable; let Room handle it
        }
        if (storedVersion > CURRENT_VERSION) {
            Log.w(
                TAG,
                "DrugRef DB schema v$storedVersion > supported v$CURRENT_VERSION. " +
                    "Deleting; will reseed from bundled asset.",
            )
            dbFile.delete()
        }
    }

    /**
     * Atomically replaces the drug-ref database with a validated CDN download (Slice 8).
     * Call this ONLY after the file passes Ed25519 manifest verification.
     * Returns false if the replacement file has an incompatible schema.
     *
     * NOTE (TODO.md §B): The CDN builder (`build_drug_db.py`) emits a raw SQLite file with
     * `user_version = 0`. Room expects `user_version` = [CURRENT_VERSION] in the file it manages.
     * A raw-SQLite CDN DB swapped in here will cause Room to trigger `fallbackToDestructiveMigration`
     * on next open and reseed from the bundled asset, discarding the CDN data. A proper CDN swap
     * requires either: (a) the builder sets `PRAGMA user_version = 2`, or (b) a post-download step
     * sets it before the swap. Dave-gated (CDN not provisioned yet) — logged in TODO.md §B.
     */
    fun swapFromFile(context: Context, newDbFile: java.io.File): Boolean {
        val newVersion = try {
            SQLiteDatabase.openDatabase(
                newDbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { it.version }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read version from CDN DB file; aborting swap.", e)
            newDbFile.delete()
            return false
        }
        if (newVersion > CURRENT_VERSION) {
            Log.w(
                TAG,
                "CDN DB schema v$newVersion > supported v$CURRENT_VERSION; " +
                    "keeping current DB until app is updated.",
            )
            newDbFile.delete()
            return false
        }
        val target = context.getDatabasePath(DATABASE_NAME)
        return try {
            newDbFile.copyTo(target, overwrite = true)
            newDbFile.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Atomic DB swap failed.", e)
            false
        }
    }

    private const val TAG = "DrugRefDatabaseFactory"

    /**
     * Seeds the drug-ref database from the bundled RxNorm asset on first creation.
     * Uses raw [SupportSQLiteDatabase] to avoid a circular dependency during the Room
     * onCreate callback.
     *
     * Seed failure (bad asset, schema drift, I/O error) degrades gracefully to an empty but valid
     * DB so the user falls back to free-text entry — a bad drug-ref DB must NEVER crash the app.
     */
    private class SeedCallback(private val context: Context) :
        androidx.room.RoomDatabase.Callback() {
        override fun onDestructiveMigration(db: SupportSQLiteDatabase) = onCreate(db)

        override fun onCreate(db: SupportSQLiteDatabase) {
            val data = try {
                BundledDrugRefLoader(context).load() ?: return
            } catch (e: Exception) {
                Log.e(TAG, "Drug-ref asset load failed; reference DB left empty.", e)
                return
            }
            db.beginTransaction()
            try {
                for (drug in data.drugs) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO drug_search (rxcui, name, name_lower, tty) " +
                            "VALUES (?,?,?,?)",
                        arrayOf(drug.rxcui, drug.name, drug.nameLower, drug.tty),
                    )
                }
                for (edge in data.ingredientEdges) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO ingredient_map " +
                            "(drug_rxcui, ingredient_rxcui, ingredient_name) VALUES (?,?,?)",
                        arrayOf(edge.drugRxcui, edge.ingredientRxcui, edge.ingredientName),
                    )
                }
                for (meta in data.metaEntries) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO db_meta (key, value) VALUES (?,?)",
                        arrayOf(meta.key, meta.value),
                    )
                }
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                Log.e(TAG, "Drug-ref seeding failed; reference DB left empty.", e)
            } finally {
                db.endTransaction()
            }
        }
    }
}
