package com.beryndil.pharos.refill

import com.beryndil.pharos.data.regimen.dao.MedicationDao
import com.beryndil.pharos.data.regimen.dao.RefillRecordDao
import com.beryndil.pharos.data.regimen.dao.ScheduleDao
import com.beryndil.pharos.data.regimen.dao.SchedulePhaseDao
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.RefillEventType
import com.beryndil.pharos.data.regimen.entity.RefillRecordEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.SchedulePhaseEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import com.beryndil.pharos.refill.RefillSummary.Companion.LOW_SUPPLY_THRESHOLD_DAYS
import com.beryndil.pharos.schedule.ScheduleEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Refill tracking repository (spec §2.9).
 *
 * All writes produce append-only [RefillRecordEntity] rows (Law 9): no refill event is ever
 * modified or deleted. The current state is always the most-recent row for a medication.
 *
 * Zero-supply invariant (spec §2.9, Law 1): this repository has no reference to the alarm
 * engine or dose state machine. A zero quantity count can NEVER suppress a dose reminder —
 * the repositories are architecturally isolated.
 *
 * Doses-per-day derivation: computed directly from the [ScheduleEntity] fields without
 * running [ScheduleEngine.generateInstances] over a time window (DECISIONS.md S7-A2).
 * PRN schedules return null (dose count is unpredictable).
 */
class RefillRepository(
    private val refillRecordDao: RefillRecordDao,
    private val medicationDao: MedicationDao,
    private val scheduleDao: ScheduleDao,
    private val schedulePhaseDao: SchedulePhaseDao,
) {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Observation ───────────────────────────────────────────────────────────

    /**
     * Observe the current [RefillSummary] for a medication.
     *
     * The flow re-emits when the latest refill record changes; medication name and schedule
     * changes are loaded once. For a fully reactive schedule-change reaction, re-subscribe
     * after a schedule edit (acceptable for v1).
     */
    fun observeRefillSummary(medicationId: String): Flow<RefillSummary> {
        val latestRecordFlow = refillRecordDao.observeByMedication(medicationId)
            .map { records -> records.firstOrNull() } // already DESC-sorted; first = latest

        return latestRecordFlow.map { latestRecord ->
            val med = medicationDao.getById(medicationId)
            val medName = med?.name.orEmpty()
            val pharmacyFromMed = med?.pharmacy

            val activeSchedules = scheduleDao.getActiveByMedicationOnce(medicationId)
            val dosesPerDay = computeDosesPerDay(activeSchedules)
            val isPrn = activeSchedules.any {
                runCatching { ScheduleType.valueOf(it.type) }.getOrNull() == ScheduleType.PRN
            } && activeSchedules.size == 1

            val quantityOnHand = latestRecord?.quantityOnHand
            val quantityUnit = latestRecord?.quantityUnit
            val refillByEpochMs = latestRecord?.refillByEpochMs
            val pharmacyPhone = latestRecord?.pharmacyPhone ?: pharmacyFromMed
            val noSupplyOnRecord = latestRecord == null
            val supplyIsZero = quantityOnHand != null && quantityOnHand == 0

            val daysUntilEmpty: Int? = when {
                isPrn -> null
                quantityOnHand == null -> null
                dosesPerDay == null || dosesPerDay <= 0.0 -> null
                else -> (quantityOnHand / dosesPerDay).toInt()
            }

            val isLowSupply = daysUntilEmpty != null && daysUntilEmpty < LOW_SUPPLY_THRESHOLD_DAYS

            RefillSummary(
                medicationId = medicationId,
                medicationName = medName,
                quantityOnHand = quantityOnHand,
                quantityUnit = quantityUnit,
                dosesPerDay = dosesPerDay,
                daysUntilEmpty = daysUntilEmpty,
                noSupplyOnRecord = noSupplyOnRecord,
                supplyIsZero = supplyIsZero,
                refillByEpochMs = refillByEpochMs,
                pharmacyPhone = pharmacyPhone,
                isPrn = isPrn,
                isLowSupply = isLowSupply,
            )
        }
    }

    /** Full append-only refill event history for a medication, newest first. */
    fun observeRefillHistory(medicationId: String): Flow<List<RefillRecordEntity>> =
        refillRecordDao.observeByMedication(medicationId)

    // ── Write operations (all append-only) ────────────────────────────────────

    /**
     * Record the initial quantity on hand when the user first sets up refill tracking for a
     * medication. Creates an [RefillEventType.INITIAL] row.
     */
    suspend fun setInitialCount(
        medicationId: String,
        quantity: Int,
        unit: String,
        pharmacyPhone: String?,
        nowMs: Long,
    ) {
        refillRecordDao.insert(
            RefillRecordEntity(
                id = UUID.randomUUID().toString(),
                medicationId = medicationId,
                quantityOnHand = quantity,
                quantityUnit = unit,
                refillByEpochMs = null,
                pharmacyPhone = pharmacyPhone,
                notes = null,
                type = RefillEventType.INITIAL.name,
                createdAtEpochMs = nowMs,
            ),
        )
    }

    /**
     * Record a full refill pickup — the user collected a new prescription.
     * The [newQuantity] is the total on hand after pickup (not a delta). Creates a
     * [RefillEventType.REFILL_PICKUP] row.
     *
     * Example: user had 5 tablets left, picked up 30 more → [newQuantity] = 35.
     */
    suspend fun recordPickup(
        medicationId: String,
        newQuantity: Int,
        unit: String,
        pharmacyPhone: String?,
        notes: String?,
        refillByEpochMs: Long?,
        nowMs: Long,
    ) {
        refillRecordDao.insert(
            RefillRecordEntity(
                id = UUID.randomUUID().toString(),
                medicationId = medicationId,
                quantityOnHand = newQuantity,
                quantityUnit = unit,
                refillByEpochMs = refillByEpochMs,
                pharmacyPhone = pharmacyPhone,
                notes = notes,
                type = RefillEventType.REFILL_PICKUP.name,
                createdAtEpochMs = nowMs,
            ),
        )
    }

    /**
     * Record a partial fill — pharmacy dispensed fewer than the prescribed quantity.
     * [additionalQuantity] is added to the current on-hand count. Creates an
     * [RefillEventType.ADJUSTMENT] row.
     */
    suspend fun recordPartialFill(
        medicationId: String,
        additionalQuantity: Int,
        unit: String,
        notes: String?,
        nowMs: Long,
    ) {
        val current = refillRecordDao.getLatest(medicationId)
        val currentCount = current?.quantityOnHand ?: 0
        val newCount = currentCount + additionalQuantity
        refillRecordDao.insert(
            RefillRecordEntity(
                id = UUID.randomUUID().toString(),
                medicationId = medicationId,
                quantityOnHand = newCount,
                quantityUnit = unit.ifBlank { current?.quantityUnit.orEmpty() },
                refillByEpochMs = current?.refillByEpochMs,
                pharmacyPhone = current?.pharmacyPhone,
                notes = notes,
                type = RefillEventType.ADJUSTMENT.name,
                createdAtEpochMs = nowMs,
            ),
        )
    }

    /**
     * Record that the user stopped taking this medication before the bottle was empty.
     * Sets on-hand count to 0 via an [RefillEventType.ADJUSTMENT] row.
     *
     * Dose reminders are not affected — that is controlled by the medication's status and
     * schedule, which this repository never touches (zero-supply invariant).
     */
    suspend fun recordStoppedBeforeEmpty(
        medicationId: String,
        nowMs: Long,
    ) {
        val current = refillRecordDao.getLatest(medicationId)
        refillRecordDao.insert(
            RefillRecordEntity(
                id = UUID.randomUUID().toString(),
                medicationId = medicationId,
                quantityOnHand = 0,
                quantityUnit = current?.quantityUnit.orEmpty(),
                refillByEpochMs = null,
                pharmacyPhone = current?.pharmacyPhone,
                notes = null,
                type = RefillEventType.ADJUSTMENT.name,
                createdAtEpochMs = nowMs,
            ),
        )
    }

    /**
     * Update the refill-by date without changing the quantity. Creates an
     * [RefillEventType.ADJUSTMENT] row with the current quantity and the new date.
     */
    suspend fun setRefillByDate(
        medicationId: String,
        refillByEpochMs: Long,
        nowMs: Long,
    ) {
        val current = refillRecordDao.getLatest(medicationId)
        refillRecordDao.insert(
            RefillRecordEntity(
                id = UUID.randomUUID().toString(),
                medicationId = medicationId,
                quantityOnHand = current?.quantityOnHand ?: 0,
                quantityUnit = current?.quantityUnit.orEmpty(),
                refillByEpochMs = refillByEpochMs,
                pharmacyPhone = current?.pharmacyPhone,
                notes = null,
                type = RefillEventType.ADJUSTMENT.name,
                createdAtEpochMs = nowMs,
            ),
        )
    }

    // ── Low-supply check (used by LowSupplyCheckWorker) ───────────────────────

    /**
     * Return summaries for all active medications that have a supply count below
     * [LOW_SUPPLY_THRESHOLD_DAYS] days, including zero-supply medications.
     *
     * Called by [LowSupplyCheckWorker] to decide which low-supply notifications to post.
     * This method never touches the alarm engine or dose state machine (zero-supply invariant).
     */
    suspend fun getLowSupplySummaries(): List<RefillSummary> {
        val activeMeds = medicationDao.getActiveOnce()
        return activeMeds.mapNotNull { med ->
            val latest = refillRecordDao.getLatest(med.id) ?: return@mapNotNull null
            val activeSchedules = scheduleDao.getActiveByMedicationOnce(med.id)
            val dosesPerDay = computeDosesPerDay(activeSchedules)
            val isPrn = activeSchedules.singleOrNull()?.let {
                runCatching { ScheduleType.valueOf(it.type) }.getOrNull() == ScheduleType.PRN
            } ?: false

            if (isPrn) return@mapNotNull null // PRN: no run-out date

            val daysUntilEmpty: Int? = if (dosesPerDay != null && dosesPerDay > 0.0) {
                (latest.quantityOnHand / dosesPerDay).toInt()
            } else null

            val isLowSupply = daysUntilEmpty != null && daysUntilEmpty < LOW_SUPPLY_THRESHOLD_DAYS
            if (!isLowSupply) return@mapNotNull null

            RefillSummary(
                medicationId = med.id,
                medicationName = med.name,
                quantityOnHand = latest.quantityOnHand,
                quantityUnit = latest.quantityUnit,
                dosesPerDay = dosesPerDay,
                daysUntilEmpty = daysUntilEmpty,
                noSupplyOnRecord = false,
                supplyIsZero = latest.quantityOnHand == 0,
                refillByEpochMs = latest.refillByEpochMs,
                pharmacyPhone = latest.pharmacyPhone ?: med.pharmacy,
                isPrn = false,
                isLowSupply = true,
            )
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Compute doses per day from the active schedule entities without generating instances
     * (DECISIONS.md S7-A2). Returns null for PRN schedules (unpredictable depletion) and
     * when no active scheduled-type schedule is found.
     *
     * For medications with multiple active schedules (e.g., a paused old version that wasn't
     * deactivated), uses the first non-PRN schedule found. In practice, the append-only model
     * deactivates old schedules before inserting new ones.
     */
    internal fun computeDosesPerDay(schedules: List<ScheduleEntity>): Double? {
        val active = schedules.filter { it.isActive }
        if (active.isEmpty()) return null

        // If there's a PRN schedule among the active ones, treat the med as PRN
        val hasPrn = active.any {
            runCatching { ScheduleType.valueOf(it.type) }.getOrNull() == ScheduleType.PRN
        }
        if (hasPrn && active.size == 1) return null

        // Compute for the first non-PRN active schedule
        val schedule = active.firstOrNull {
            runCatching { ScheduleType.valueOf(it.type) }.getOrNull() != ScheduleType.PRN
        } ?: return null

        return computeDosesPerDayForSchedule(schedule)
    }

    /**
     * Compute doses per day for a single schedule entity.
     * TAPER schedules require phase data; fetched synchronously (called on IO dispatcher).
     */
    private fun computeDosesPerDayForSchedule(schedule: ScheduleEntity): Double? {
        return when (runCatching { ScheduleType.valueOf(schedule.type) }.getOrNull()) {
            ScheduleType.PRN -> null

            ScheduleType.FIXED_DAILY,
            ScheduleType.TEMPORARY,
            -> {
                val count = ScheduleEngine.parseTimes(schedule.scheduledTimesJson).size
                if (count == 0) null else count.toDouble()
            }

            ScheduleType.DAYS_OF_WEEK -> {
                val times = ScheduleEngine.parseTimes(schedule.scheduledTimesJson).size
                val days = parseDaysOfWeek(schedule.daysOfWeekJson).size
                if (times == 0 || days == 0) null else times * days / 7.0
            }

            ScheduleType.INTERVAL -> {
                val hours = schedule.intervalHours ?: return null
                if (hours <= 0) null else 24.0 / hours
            }

            ScheduleType.DOSE_WINDOW -> 1.0

            ScheduleType.TAPER -> null // requires phases; handled separately via computeDosesPerDayForTaper

            null -> null
        }
    }

    /** Compute doses/day for a taper schedule given its phase list. */
    internal fun computeDosesPerDayForTaper(phases: List<SchedulePhaseEntity>): Double? {
        if (phases.isEmpty()) return null
        val totalDoses = phases.sumOf { phase ->
            ScheduleEngine.parseTimes(phase.scheduledTimesJson).size * phase.durationDays
        }
        val totalDays = phases.sumOf { it.durationDays }
        return if (totalDays == 0) null else totalDoses.toDouble() / totalDays
    }

    /** Parse a JSON array of ISO weekday integers into a set. */
    private fun parseDaysOfWeek(jsonOrNull: String?): Set<Int> {
        if (jsonOrNull.isNullOrBlank()) return emptySet()
        return try {
            json.decodeFromString<List<Int>>(jsonOrNull).toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
