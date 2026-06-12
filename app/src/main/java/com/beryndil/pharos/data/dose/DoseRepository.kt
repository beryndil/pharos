package com.beryndil.pharos.data.dose

import com.beryndil.pharos.data.regimen.dao.DoseInstanceDao
import com.beryndil.pharos.data.regimen.dao.DoseTransitionDao
import com.beryndil.pharos.data.regimen.dao.MedicationDao
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
    private val stateMachine: DoseStateMachine,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
) {

    /** Today's actionable + upcoming doses (DUE / SNOOZED / SCHEDULED before tomorrow). */
    fun observeTodayDoses(): Flow<List<DoseRow>> {
        val before = startOfTomorrowEpochMs()
        return combine(
            doseInstanceDao.observeActionable(before),
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
    val dueEpochMs: Long,
    val state: DoseState,
)
