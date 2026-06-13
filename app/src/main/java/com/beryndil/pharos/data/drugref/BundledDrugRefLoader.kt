package com.beryndil.pharos.data.drugref

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.beryndil.pharos.data.drugref.entity.DbMetaEntity
import com.beryndil.pharos.data.drugref.entity.DrugSearchEntity
import com.beryndil.pharos.data.drugref.entity.IngredientMapEntity
import java.io.File
import java.io.FileOutputStream

/**
 * Loads drug-reference data from the bundled RxNorm SQLite asset.
 *
 * Bundle contract:
 *  - Asset name: [ASSET_NAME]
 *  - Format: raw SQLite 3 database (NOT a Room-managed file).
 *  - Tables read: `drug_search`, `ingredient_map`, `db_meta`.
 *  - FTS tables (`drug_fts` and its shadow tables) are ignored by the loader.
 *
 * This asset seeds [DrugRefDatabase] on first install (via [DrugRefDatabaseFactory.SeedCallback]).
 * CDN updates replace the Room-managed database file atomically via the download pipeline (Slice 8).
 *
 * The asset is a ~400-drug representative subset of RxNorm (~360 KB). Real production data ships
 * via CDN; the bundled asset ensures the app functions offline immediately after install.
 */
class BundledDrugRefLoader(private val context: Context) {

    /**
     * Copies the bundled asset to a temp file, reads its tables, and returns structured data.
     * Returns null if the asset is missing or cannot be opened.
     *
     * The temp file is deleted before this method returns.
     */
    fun load(): FixtureData? {
        val tmpFile = File(context.cacheDir, "drug_ref_seed_tmp.db")
        return try {
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
            }
            readAsset(tmpFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bundled drug-ref asset '$ASSET_NAME'", e)
            null
        } finally {
            tmpFile.delete()
        }
    }

    private fun readAsset(file: File): FixtureData {
        val drugs = mutableListOf<DrugSearchEntity>()
        val edges = mutableListOf<IngredientMapEntity>()
        val meta = mutableListOf<DbMetaEntity>()

        SQLiteDatabase.openDatabase(
            file.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery(
                "SELECT rxcui, name, name_lower, tty FROM drug_search",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    drugs += DrugSearchEntity(
                        rxcui = cursor.getString(0),
                        name = cursor.getString(1),
                        nameLower = cursor.getString(2),
                        tty = cursor.getString(3),
                    )
                }
            }

            db.rawQuery(
                "SELECT drug_rxcui, ingredient_rxcui, ingredient_name FROM ingredient_map",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    edges += IngredientMapEntity(
                        drugRxcui = cursor.getString(0),
                        ingredientRxcui = cursor.getString(1),
                        ingredientName = cursor.getString(2),
                    )
                }
            }

            db.rawQuery("SELECT key, value FROM db_meta", null).use { cursor ->
                while (cursor.moveToNext()) {
                    meta += DbMetaEntity(
                        key = cursor.getString(0),
                        value = cursor.getString(1),
                    )
                }
            }
        }
        return FixtureData(drugs, edges, meta)
    }

    /** Structured data returned from the bundled asset. */
    data class FixtureData(
        val drugs: List<DrugSearchEntity>,
        val ingredientEdges: List<IngredientMapEntity>,
        val metaEntries: List<DbMetaEntity>,
    )

    companion object {
        /** Asset filename for the bundled drug-reference SQLite database. */
        const val ASSET_NAME = "drug_ref.db"
        private const val TAG = "BundledDrugRefLoader"
    }
}
