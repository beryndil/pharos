package com.beryndil.pharos.data.drugref

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.beryndil.pharos.data.drugref.entity.IngredientEntity
import com.beryndil.pharos.data.drugref.entity.ProductEntity
import java.io.File
import java.io.FileOutputStream

/**
 * Loads the trimmed RxNorm fixture from the bundled raw SQLite asset.
 *
 * Bundle contract:
 * - Asset name: [ASSET_NAME]
 * - Format: raw SQLite 3 database (NOT a Room-managed file — no room_master_table required).
 * - Schema version (PRAGMA user_version): 1 for v1 fixture.
 * - Tables:
 *   - `ingredients` (rxcui TEXT PK, name TEXT, tty TEXT)
 *   - `products`    (rxcui TEXT PK, name TEXT, ingredients_json TEXT, form TEXT, strength TEXT)
 *
 * This fixture is used to seed [DrugRefDatabase] on first install. CDN updates replace the
 * Room-managed database file atomically via the download pipeline (Slice 8).
 *
 * The asset is a small offline bundle (~tens of KB). Real production data comes via CDN;
 * the fixture ensures the app functions offline immediately after install.
 */
class BundledDrugRefLoader(private val context: Context) {

    /**
     * Copies the bundled asset to a temp file, reads its contents, and returns structured data.
     * Returns null if the asset is missing or the database cannot be opened.
     *
     * The caller (DrugRefDatabase onCreate callback) inserts the returned data into Room.
     * The temp file is deleted before this method returns.
     */
    fun load(): FixtureData? {
        val tmpFile = File(context.cacheDir, "drug_ref_seed_tmp.db")
        return try {
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
            }
            readFixture(tmpFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bundled drug-ref fixture from asset '$ASSET_NAME'", e)
            null
        } finally {
            tmpFile.delete()
        }
    }

    private fun readFixture(file: File): FixtureData {
        val ingredients = mutableListOf<IngredientEntity>()
        val products = mutableListOf<ProductEntity>()

        SQLiteDatabase.openDatabase(
            file.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("SELECT rxcui, name, tty FROM ingredients", null).use { cursor ->
                while (cursor.moveToNext()) {
                    ingredients += IngredientEntity(
                        rxcui = cursor.getString(0),
                        name = cursor.getString(1),
                        tty = cursor.getString(2),
                    )
                }
            }
            db.rawQuery(
                "SELECT rxcui, name, ingredients_json, form, strength FROM products",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    products += ProductEntity(
                        rxcui = cursor.getString(0),
                        name = cursor.getString(1),
                        ingredientsJson = cursor.getString(2),
                        form = cursor.getString(3),
                        strength = cursor.getString(4),
                    )
                }
            }
        }
        return FixtureData(ingredients, products)
    }

    /** Structured data returned from the bundled fixture. */
    data class FixtureData(
        val ingredients: List<IngredientEntity>,
        val products: List<ProductEntity>,
    )

    companion object {
        /** Asset file name for the bundled RxNorm fixture SQLite database. */
        const val ASSET_NAME = "drug_ref_fixture.db"
        private const val TAG = "BundledDrugRefLoader"
    }
}
