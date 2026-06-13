package com.beryndil.pharos.data.drugref.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Key-value provenance record from the drug-DB build pipeline (table `db_meta`).
 *
 * Keys in the bundled DB (from `build_bundled_db.py`):
 *  - `source`               — human-readable data origin, e.g. "RxNorm via RxNav REST API (NLM)"
 *  - `built`                — ISO-8601 date the DB was built, e.g. "2026-06-12"
 *  - `drug_count`           — number of `drug_search` rows
 *  - `ingredient_edge_count`— number of `ingredient_map` rows
 *  - `bundled_subset`       — "true" when this is the APK-bundled subset
 *  - `fts5`                 — "yes" when the FTS5 virtual table was created
 *
 * The full CDN DB (from `build_drug_db.py`) uses slightly different keys (`name_count`,
 * `ingredient_edges`) — the DAO's [DbMetaDao.get] returns null for absent keys so callers
 * use `.getOrDefault()` for forward compatibility (DECISIONS.md G2a-7).
 *
 * Surfaced in the drug-reference UI (Law 9: every record shows source + freshness date).
 */
@Entity(tableName = "db_meta")
data class DbMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
