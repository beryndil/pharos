package com.beryndil.pharos.data.drugref

import androidx.room.Database
import androidx.room.RoomDatabase
import com.beryndil.pharos.data.drugref.dao.IngredientDao
import com.beryndil.pharos.data.drugref.dao.LabelCacheDao
import com.beryndil.pharos.data.drugref.dao.ProductDao
import com.beryndil.pharos.data.drugref.entity.IngredientEntity
import com.beryndil.pharos.data.drugref.entity.LabelCacheEntity
import com.beryndil.pharos.data.drugref.entity.ProductEntity

/**
 * Room database for public drug-reference data (plaintext — spec §3.3, DECISIONS.md A3).
 *
 * This database holds:
 * - Trimmed RxNorm ingredients/products (from the bundled fixture or CDN update).
 * - Cached openFDA label text with source + freshness date (Law 9).
 *
 * It is NEVER written to by regimen operations — the two databases are strictly separated
 * (Standards §1: "Two Room databases, never cross-joined").
 *
 * Factory methods, the CDN swap helper, and the newer-schema guard live in
 * [DrugRefDatabaseFactory] to keep this class clean for Room's KSP annotation processor.
 */
@Database(
    entities = [
        IngredientEntity::class,
        ProductEntity::class,
        LabelCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DrugRefDatabase : RoomDatabase() {

    abstract fun ingredientDao(): IngredientDao
    abstract fun productDao(): ProductDao
    abstract fun labelCacheDao(): LabelCacheDao
}
