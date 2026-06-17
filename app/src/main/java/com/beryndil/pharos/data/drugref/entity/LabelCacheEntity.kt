package com.beryndil.pharos.data.drugref.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached drug label text fetched from openFDA for a specific product (spec §2.10).
 *
 * Label text is cached locally; a refresh wipes the entry so the next screen open re-fetches.
 * The [source] and [fetchedAtEpochMs] fields are displayed alongside the reference text
 * (Law 9: every record shows source + freshness date). Null text fields mean the section was
 * not present in the source; those sections are hidden in the UI.
 */
@Entity(tableName = "label_cache")
data class LabelCacheEntity(
    /** RxCUI of the drug concept this label belongs to (same as [MedicationEntity.rxcui]). */
    @PrimaryKey val productRxcui: String,

    val sideEffectsText: String?,
    val interactionsText: String?,
    val warningsText: String?,
    val precautionsText: String?,
    val contraindicationsText: String?,
    val boxedWarningText: String?,
    val foodEffectText: String? = null,
    val brandName: String? = null,

    /**
     * Human-readable source identifier, e.g., "openFDA".
     * Displayed to the user (Law 9).
     */
    val source: String,

    /** UTC epoch-ms when this label was fetched. Displayed as freshness date (Law 9). */
    val fetchedAtEpochMs: Long,
)
