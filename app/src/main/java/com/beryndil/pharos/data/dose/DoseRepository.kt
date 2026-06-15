package com.beryndil.pharos.data.dose

import androidx.compose.runtime.Immutable
import com.beryndil.pharos.data.regimen.dao.DoseInstanceDao
import com.beryndil.pharos.data.regimen.dao.DoseTransitionDao
import com.beryndil.pharos.data.regimen.dao.MedicationDao
import com.beryndil.pharos.data.regimen.dao.ScheduleDao
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.data.regimen.entity.DoseTransitionEntity
import com.beryndil.pharos.dose.DoseStateMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.ZoneId

/**
 * Read/act facade over the dose data for the today and history UIs (Slice 5). Transitions are
 * delegated to the single authority ([DoseStateMachine]); this repository never mutates dose state
 * itself.
 */
class DoseRepository(
    private val doseInstanceDao: DoseInstanceDao,
    private val doseTransitionDao: DoseTransitionDao,
    private val medicationDao: MedicationDao,
    private val scheduleDao: ScheduleDao,
    private val stateMachine: DoseStateMachine,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
) {

    /** Today's actionable + upcoming doses (DUE / SNOOZED / SCHEDULED before tomorrow). */
    fun observeTodayDoses(): Flow<List<DoseRow>> {
        val scheduledFrom = startOfDayEpochMs()
        val before = startOfTomorrowEpochMs()
        return combine(
            doseInstanceDao.observeActionable(scheduledFrom, before),
            medicationDao.observeAll(),
        ) { doses, meds ->
            val byId = meds.associateBy { it.id }
            doses.mapNotNull { d ->
                byId[d.medicationId]?.let { m ->
                    DoseRow(
                        doseId = d.id,
                        medicationId = d.medicationId,
                        medName = m.name,
                        strength = m.strength,
                        doseAmount = m.doseAmount,
                        dueEpochMs = d.dueEpochMs,
                        state = DoseState.valueOf(d.state),
                    )
                }
            }
        }
    }

    /** Append-only transition history for one medication (newest first). */
    fun observeHistory(medicationId: String): Flow<List<DoseTransitionEntity>> =
        doseTransitionDao.observeByMedication(medicationId)

    suspend fun medicationName(medicationId: String): String =
        medicationDao.getById(medicationId)?.name.orEmpty()

    suspend fun take(doseId: String) = stateMachine.onTaken(doseId, now())

    suspend fun snooze(doseId: String) = stateMachine.onSnooze(doseId, now())

    suspend fun skip(doseId: String) = stateMachine.onSkip(doseId, now())

    /** Log a PRN dose (spec §2.7). Returns the per-day count + non-blocking daily-max warning. */
    suspend fun logPrn(medicationId: String, scheduleId: String) =
        stateMachine.logPrnTaken(medicationId, scheduleId)

    /**
     * Observe the set of active PRN medications for the Today screen (spec §2.7).
     *
     * PRN doses are user-initiated logs only — no SCHEDULED/MISSED states. This flow returns
     * one [PrnMedRow] per active PRN schedule, including the number of doses logged today so
     * the UI can show "Logged today: N" and the configured daily-max (for the non-blocking
     * advisory warning, Law 3).
     *
     * Note: [startOfDayEpochMs] is computed once when the flow is first collected. If the day
     * rolls over while the app is open the count resets on the next ViewModel creation (acceptable
     * for v1 — DECISIONS.md A2-PRN-1).
     */
    fun observePrnMeds(): Flow<List<PrnMedRow>> {
        val sinceMs = startOfDayEpochMs()
        return combine(
            scheduleDao.observeAllActivePrn(),
            medicationDao.observeAll(),
            doseInstanceDao.observeAllTakenSince(sinceMs),
        ) { prnSchedules, meds, takenToday ->
            val medById = meds.associateBy { it.id }
            val takenCountByMed = takenToday.groupBy { it.medicationId }.mapValues { it.value.size }
            prnSchedules.mapNotNull { sched ->
                val med = medById[sched.medicationId] ?: return@mapNotNull null
                PrnMedRow(
                    medicationId = med.id,
                    scheduleId = sched.id,
                    medName = med.name,
                    strength = med.strength,
                    doseAmount = med.doseAmount,
                    indication = sched.indication ?: med.purpose,
                    dosesToday = takenCountByMed[med.id] ?: 0,
                    dailyMax = sched.dailyMaxDoses,
                )
            }
        }
    }

    private fun startOfDayEpochMs(): Long {
        val zone = zoneProvider()
        return Instant.ofEpochMilli(now())
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    private fun startOfTomorrowEpochMs(): Long {
        val zone = zoneProvider()
        return Instant.ofEpochMilli(now())
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }
}

/** A dose row for the today/upcoming surface. */
data class DoseRow(
    val doseId: String,
    val medicationId: String,
    val medName: String,
    val strength: String,
    val doseAmount: String,
    val dueEpochMs: Long,
    val state: DoseState,
)

/**
 * A PRN medication row for the Today screen "As needed" section (spec §2.7).
 *
 * PRN doses have no SCHEDULED or MISSED states — they exist only as user-initiated logs.
 * [dosesToday] is the count of TAKEN instances logged since midnight; used to display
 * "Logged today: N" and trigger the non-blocking daily-max advisory (Law 3).
 */
@Immutable
data class PrnMedRow(
    val medicationId: String,
    val scheduleId: String,
    val medName: String,
    val strength: String,
    val doseAmount: String,
    val indication: String?,
    val dosesToday: Int,
    val dailyMax: Int?,
)
