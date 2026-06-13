package com.beryndil.pharos.data.drugref

import androidx.room.Database
import androidx.room.RoomDatabase
import com.beryndil.pharos.data.drugref.dao.DbMetaDao
import com.beryndil.pharos.data.drugref.dao.DrugSearchDao
import com.beryndil.pharos.data.drugref.dao.IngredientMapDao
import com.beryndil.pharos.data.drugref.dao.LabelCacheDao
import com.beryndil.pharos.data.drugref.entity.DbMetaEntity
import com.beryndil.pharos.data.drugref.entity.DrugSearchEntity
import com.beryndil.pharos.data.drugref.entity.IngredientMapEntity
import com.beryndil.pharos.data.drugref.entity.LabelCacheEntity

/**
 * Room database for public drug-reference data (plaintext — spec §3.3, DECISIONS.md A3).
 *
 * Schema version 2 aligns to the RxNorm pipeline output from `build_drug_db.py` / `build_bundled_db.py`
 * (DECISIONS.md G2b).  Tables:
 *  - `drug_search`    — RxNorm drug concepts (IN/PIN/MIN/BN/SCD/SBD…); name-search index.
 *  - `ingredient_map` — drug→ingredient edges; drives duplicate-ingredient detection (spec §2.4).
 *  - `db_meta`        — provenance (source, built date, counts); surfaced in the UI (Law 9).
 *  - `label_cache`    — cached openFDA label text with source + freshness date (Law 9); runtime
 *                        only, never touched by the DB build pipeline.
 *
 * Drug-ref data is public and replaceable (not PHI). On a schema version mismatch the database
 * file is destroyed and reseeded from the bundled asset (see [DrugRefDatabaseFactory]).
 *
 * Factory methods, the CDN swap helper, and the newer-schema guard live in
 * [DrugRefDatabaseFactory] to keep this class clean for Room's KSP annotation processor.
 */
@Database(
    entities = [
        DrugSearchEntity::class,
        IngredientMapEntity::class,
        DbMetaEntity::class,
        LabelCacheEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class DrugRefDatabase : RoomDatabase() {

    abstract fun drugSearchDao(): DrugSearchDao
    abstract fun ingredientMapDao(): IngredientMapDao
    abstract fun dbMetaDao(): DbMetaDao
    abstract fun labelCacheDao(): LabelCacheDao
}
