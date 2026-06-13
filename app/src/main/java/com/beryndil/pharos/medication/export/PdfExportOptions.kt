package com.beryndil.pharos.medication.export

/**
 * Controls which fields and which medications appear in the exported PDF.
 *
 * All field flags default to true (include everything). The [statusFilter] controls
 * which medications are included based on their lifecycle status.
 *
 * Immutable; pass through the event/viewmodel chain without copy-risk.
 */
data class PdfExportOptions(
    /** Include the dose amount field (e.g. "1 tablet"). */
    val includeDoseAmount: Boolean = true,
    /** Include the schedule (times, frequency). */
    val includeSchedule: Boolean = true,
    /** Include prescriber name and phone. */
    val includePrescriber: Boolean = true,
    /** Include pharmacy name and phone. */
    val includePharmacy: Boolean = true,
    /** Include the purpose / condition note. */
    val includePurpose: Boolean = true,
    /** Include the current supply / refill-by date. */
    val includeSupply: Boolean = true,
    /** Which lifecycle statuses to include in the export. */
    val statusFilter: PdfStatusFilter = PdfStatusFilter.ACTIVE_AND_PAUSED,
)

enum class PdfStatusFilter {
    /** Active medications only. */
    ACTIVE_ONLY,
    /** Active and paused medications (default — paused meds still matter clinically). */
    ACTIVE_AND_PAUSED,
    /** All medications including ended/discontinued. */
    ALL,
}
