package com.beryndil.pharos.data.drugref.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached drug label text fetched from openFDA/DailyMed for a specific product (spec §2.10).
 *
 * Label text is cached locally forever after first fetch. The [source] and [fetchedAtEpochMs]
 * fields are displayed alongside the reference text (Law 9: every record shows source +
 * freshness date). A null text field means the section was not available in the source data;
 * the UI says "reference not available" rather than nothing.
 */
@Entity(tableName = "label_cache")
data class LabelCacheEntity(
    /** RxCUI of the product this label belongs to. Links to [ProductEntity.rxcui]. */
    @PrimaryKey val productRxcui: String,

    /** Adverse reactions / side effects section text, or null if unavailable. */
    val sideEffectsText: String?,

    /** Drug interactions section text, or null if unavailable. */
    val interactionsText: String?,

    /**
     * Human-readable source identifier, e.g., "openFDA" or "DailyMed".
     * Displayed to the user (Law 9).
     */
    val source: String,

    /** UTC epoch-ms when this label was fetched. Displayed as freshness date (Law 9). */
    val fetchedAtEpochMs: Long,
)
