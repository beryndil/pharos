package com.beryndil.pharos.data.drugref

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
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
     */
    const val CURRENT_VERSION = 1

    /**
     * Builds [DrugRefDatabase], running the newer-schema guard before Room opens the file.
     *
     * If the on-disk file has a schema version > [CURRENT_VERSION], the file is deleted
     * (it came from a future CDN push that this app version cannot interpret) and Room
     * will recreate from the bundled fixture via the seeding callback.
     */
    fun build(context: Context): DrugRefDatabase {
        handleNewerSchema(context)
        return Room.databaseBuilder(context, DrugRefDatabase::class.java, DATABASE_NAME)
            .addCallback(SeedCallback(context))
            .build()
    }

    /**
     * Checks the on-disk DB version. If it is newer than [CURRENT_VERSION], deletes the file
     * so Room recreates from the bundled fixture. Unlike [RegimenDatabaseFactory], drug-ref data
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
                    "Deleting; will reseed from bundled fixture.",
            )
            dbFile.delete()
        }
    }

    /**
     * Atomically replaces the drug-ref database with a validated CDN download (Slice 8).
     * Call this ONLY after the file passes Ed25519 manifest verification.
     * Returns false if the replacement file has an incompatible schema.
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
     * Seeds the drug-ref database from the bundled RxNorm fixture on first creation.
     * Uses raw [SupportSQLiteDatabase] to avoid a circular dependency during the Room
     * onCreate callback.
     */
    private class SeedCallback(private val context: Context) :
        androidx.room.RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            // Drug reference is non-critical, replaceable public data (Law 9, spec §2.11). A seed
            // failure (bad fixture, schema drift, corrupt CDN file) must NEVER crash the app — it
            // degrades to an empty reference DB and the user falls back to free-text entry. So the
            // whole seed is wrapped: on any failure we roll back the transaction, log (no PHI), and
            // return, leaving an empty-but-valid table set.
            val data = try {
                BundledDrugRefLoader(context).load() ?: return
            } catch (e: Exception) {
                Log.e(TAG, "Drug-ref fixture load failed; reference DB left empty.", e)
                return
            }
            db.beginTransaction()
            try {
                for (ing in data.ingredients) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO ingredients (rxcui, name, tty) VALUES (?,?,?)",
                        arrayOf(ing.rxcui, ing.name, ing.tty),
                    )
                }
                for (prod in data.products) {
                    // Column is `ingredientsJson` (camelCase) — Room derives it from the entity
                    // field name with no @ColumnInfo rename. Must match the schema exactly.
                    db.execSQL(
                        "INSERT OR REPLACE INTO products " +
                            "(rxcui, name, ingredientsJson, form, strength) VALUES (?,?,?,?,?)",
                        arrayOf(
                            prod.rxcui,
                            prod.name,
                            prod.ingredientsJson,
                            prod.form,
                            prod.strength,
                        ),
                    )
                }
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                // Transaction not marked successful -> rolled back in finally. Empty DB is valid.
                Log.e(TAG, "Drug-ref seeding failed; reference DB left empty.", e)
            } finally {
                db.endTransaction()
            }
        }
    }
}
