package com.beryndil.pharos.dose

import com.beryndil.pharos.alarm.DoseActionHandler
import com.beryndil.pharos.alarm.DoseDueListener
import com.beryndil.pharos.alarm.DoseNotifier
import com.beryndil.pharos.data.regimen.dao.DoseInstanceDao
import com.beryndil.pharos.data.regimen.dao.DoseTransitionDao
import com.beryndil.pharos.data.regimen.dao.MedicationDao
import com.beryndil.pharos.data.regimen.dao.ScheduleDao
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.data.regimen.entity.DoseTransitionCause
import com.beryndil.pharos.data.regimen.entity.DoseTransitionEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The single authority for every dose state transition (spec §2.6 — the heart of the app).
 *
 * Design invariants:
 *  - **Append/transition-only (Law 9).** Every transition writes one immutable
 *    [DoseTransitionEntity] row AND updates the projection state on the [DoseInstanceEntity] via a
 *    narrow DAO UPDATE. A past transition is never overwritten or deleted.
 *  - **Legal transitions only.** Each transition is checked against [DoseTransition.isLegal];
 *    illegal transitions throw [IllegalDoseTransitionException] (Standards §1 — never silently
 *    swallow a programming error).
 *  - **Dose independence (Law 3).** Every instance transitions on its OWN row, keyed by its own
 *    timed alarms. Missing 08:00 has zero effect on 12:00. The app records outcomes; it never
 *    combines doses or advises (no skip/double/make-up anywhere).
 *  - **D2 miss window / D3 snooze.** Miss-window close is [MissWindow.closeEpochMs]; snooze is a
 *    15-minute deferral that can never push a dose past its miss window.
 *
 * It plugs into the Slice 4 alarm engine as both the [DoseDueListener] (notified when a dose
 * enters DUE) and the [DoseActionHandler] (the Take/Snooze/Skip actions). Timed transitions
 * (re-alert, miss check) are driven by [DoseTransitionScheduler] so they fire app-closed.
 */
class DoseStateMachine(
    private val doseInstanceDao: DoseInstanceDao,
    private val doseTransitionDao: DoseTransitionDao,
    private val medicationDao: MedicationDao,
    private val scheduleDao: ScheduleDao,
    private val transitionScheduler: DoseTransitionScheduler,
    private val notifier: DoseNotifier,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val idProvider: () -> String = { UUID.randomUUID().toString() },
) : DoseActionHandler, DoseDueListener {

    // ── DoseDueListener: a dose just entered DUE (the alarm fired) ──────────────────────────

    /**
     * Invoked by [com.beryndil.pharos.alarm.AlarmCoordinator] right after it marks a dose DUE and
     * posts the first alert. Records the SCHEDULED → DUE transition, then arms the D2 miss-window
     * deadline and the first escalation re-alert. If the dose is already past its window (the
     * device was off across the whole window), it goes straight to MISSED.
     */
    override suspend fun onEnteredDue(doseId: String, firedAtEpochMs: Long) {
        val dose = doseInstanceDao.getById(doseId) ?: return
        if (dose.state != DoseState.DUE.name) return

        if (doseTransitionDao.countByDose(doseId) == 0) {
            recordTransition(dose, DoseState.SCHEDULED, DoseState.DUE, DoseTransitionCause.ALARM_FIRED, firedAtEpochMs)
        }

        val missClose = computeMissClose(dose)
        if (now() >= missClose) {
            transitionToMissed(dose, now())
            return
        }
        transitionScheduler.scheduleMissCheck(doseId, missClose)
        val firstReAlert = minOf(firedAtEpochMs + ESCALATION_INTERVAL_MS, missClose)
        if (firstReAlert < missClose) transitionScheduler.scheduleReAlert(doseId, firstReAlert)
    }

    // ── Timed transitions (fired by AlarmReceiver, app-closed safe) ─────────────────────────

    /**
     * The escalating re-alert (DUE) or snooze-wake (SNOOZED) tick. A snoozed dose returns to DUE;
     * a DUE dose re-alerts at the next intensity. Either path becomes MISSED once the window has
     * closed (D2/D3). A dose already in a terminal/handled state simply cancels its timers.
     */
    suspend fun onReAlert(doseId: String) {
        val dose = doseInstanceDao.getById(doseId) ?: return
        val missClose = computeMissClose(dose)
        val nowMs = now()
        when (dose.state) {
            DoseState.SNOOZED.name -> {
                if (nowMs >= missClose) {
                    transitionToMissed(dose, nowMs)
                } else {
                    doseInstanceDao.markDueFromSnooze(doseId)
                    recordTransition(dose, DoseState.SNOOZED, DoseState.DUE, DoseTransitionCause.SNOOZE_ELAPSED, nowMs)
                    notifier.postDoseDueAlert(doseId, medName(dose), dose.dueEpochMs, 0, isCritical(dose))
                    scheduleNextReAlert(doseId, nowMs, missClose)
                }
            }

            DoseState.DUE.name -> {
                if (nowMs >= missClose) {
                    transitionToMissed(dose, nowMs)
                } else {
                    val level = escalationLevel(dose.dueEpochMs, nowMs)
                    notifier.postDoseDueAlert(doseId, medName(dose), dose.dueEpochMs, level, isCritical(dose))
                    scheduleNextReAlert(doseId, nowMs, missClose)
                }
            }

            else -> transitionScheduler.cancelTimers(doseId)
        }
    }

    /** The hard D2 miss-window deadline. Becomes MISSED if still DUE/SNOOZED at the close. */
    suspend fun onMissCheck(doseId: String) {
        val dose = doseInstanceDao.getById(doseId) ?: return
        if (dose.state != DoseState.DUE.name && dose.state != DoseState.SNOOZED.name) return
        val missClose = computeMissClose(dose)
        if (now() >= missClose) {
            transitionToMissed(dose, now())
        } else {
            // The next dose moved later (re-generation); re-arm at the new close.
            transitionScheduler.scheduleMissCheck(doseId, missClose)
        }
    }

    // ── DoseActionHandler: user-initiated transitions ───────────────────────────────────────

    override suspend fun onTaken(doseId: String, atEpochMs: Long) {
        val dose = doseInstanceDao.getById(doseId) ?: return
        val from = DoseState.valueOf(dose.state)
        requireLegal(from, DoseState.TAKEN)
        doseInstanceDao.markTaken(doseId, atEpochMs)
        recordTransition(dose, from, DoseState.TAKEN, DoseTransitionCause.USER_TAKEN, atEpochMs)
        clearAlerts(doseId)
    }

    override suspend fun onSkip(doseId: String, atEpochMs: Long) {
        val dose = doseInstanceDao.getById(doseId) ?: return
        val from = DoseState.valueOf(dose.state)
        requireLegal(from, DoseState.SKIPPED)
        // Law 3: SKIPPED is recorded with no advice. The app never tells the user to skip; it only
        // records that the user, on their own initiative, chose to.
        doseInstanceDao.markSkipped(doseId, atEpochMs)
        recordTransition(dose, from, DoseState.SKIPPED, DoseTransitionCause.USER_SKIPPED, atEpochMs)
        clearAlerts(doseId)
    }

    override suspend fun onSnooze(doseId: String, atEpochMs: Long) {
        val dose = doseInstanceDao.getById(doseId) ?: return
        val from = DoseState.valueOf(dose.state)
        requireLegal(from, DoseState.SNOOZED)
        val missClose = computeMissClose(dose)
        // D3: a snooze can never push a dose past its miss window. If the 15-min target would land
        // at/after the window close, cap the wake at the close — where it converts to MISSED.
        val target = minOf(atEpochMs + SNOOZE_INTERVAL_MS, missClose)
        doseInstanceDao.markSnoozed(doseId, target)
        recordTransition(dose, from, DoseState.SNOOZED, DoseTransitionCause.USER_SNOOZED, atEpochMs)
        notifier.cancelDoseAlert(doseId)
        transitionScheduler.scheduleReAlert(doseId, target)
        transitionScheduler.scheduleMissCheck(doseId, missClose)
    }

    // ── PRN (spec §2.7): user-initiated logs only — no SCHEDULED/MISSED ──────────────────────

    /**
     * Log a PRN ("as needed") dose as TAKEN. PRN doses have no SCHEDULED or MISSED states — they
     * exist only as user-initiated logs. Returns the per-day count and whether a daily-max warning
     * applies. The warning is **non-blocking** (Law 3): the dose is always logged.
     */
    suspend fun logPrnTaken(medicationId: String, scheduleId: String): PrnLogResult {
        val nowMs = now()
        val startOfDay = startOfDayEpochMs(nowMs)
        val priorToday = doseInstanceDao.countTakenSince(medicationId, startOfDay)
        val doseNumber = priorToday + 1

        val id = idProvider()
        val dose = DoseInstanceEntity(
            id = id,
            medicationId = medicationId,
            scheduleId = scheduleId,
            dueEpochMs = nowMs,
            windowEndEpochMs = null,
            state = DoseState.TAKEN.name,
            takenEpochMs = nowMs,
            skippedEpochMs = null,
            missedEpochMs = null,
            snoozeUntilEpochMs = null,
            createdAtEpochMs = nowMs,
        )
        doseInstanceDao.insert(dose)
        recordTransition(dose, null, DoseState.TAKEN, DoseTransitionCause.USER_TAKEN, nowMs)

        val dailyMax = scheduleDao.getById(scheduleId)?.dailyMaxDoses
        val exceedsMax = dailyMax != null && doseNumber > dailyMax
        return PrnLogResult(doseNumber = doseNumber, dailyMax = dailyMax, exceedsMax = exceedsMax)
    }

    // ── internals ───────────────────────────────────────────────────────────────────────────

    private suspend fun transitionToMissed(dose: DoseInstanceEntity, atEpochMs: Long) {
        val from = DoseState.valueOf(dose.state)
        if (!DoseTransition.isLegal(from, DoseState.MISSED)) return
        doseInstanceDao.markMissed(dose.id, atEpochMs)
        recordTransition(dose, from, DoseState.MISSED, DoseTransitionCause.MISS_WINDOW_CLOSED, atEpochMs)
        clearAlerts(dose.id)
    }

    private suspend fun computeMissClose(dose: DoseInstanceEntity): Long {
        val isWindowed = scheduleDao.getById(dose.scheduleId)?.type == ScheduleType.DOSE_WINDOW.name
        val nextDue = doseInstanceDao
            .getNextScheduledAfter(dose.medicationId, dose.dueEpochMs)
            ?.dueEpochMs
        // Read the per-medication miss window (spec §2.6 D2). Fall back to GRACE_MS if the
        // medication row cannot be found (should not happen in normal operation).
        val graceLengthMs = medicationDao.getById(dose.medicationId)
            ?.missWindowMinutes
            ?.let { it.toLong() * 60L * 1000L }
            ?: MissWindow.GRACE_MS
        return MissWindow.closeEpochMs(dose.dueEpochMs, isWindowed, dose.windowEndEpochMs, nextDue, graceLengthMs)
    }

    private fun scheduleNextReAlert(doseId: String, nowMs: Long, missClose: Long) {
        val next = nowMs + ESCALATION_INTERVAL_MS
        // Stop re-alerting once the next tick would reach the window — the miss-check owns the
        // deadline, so a redundant re-alert at the close is unnecessary.
        if (next < missClose) transitionScheduler.scheduleReAlert(doseId, next)
    }

    private fun escalationLevel(dueEpochMs: Long, nowMs: Long): Int {
        val elapsed = (nowMs - dueEpochMs).coerceAtLeast(0L)
        return (elapsed / ESCALATION_INTERVAL_MS).toInt().coerceIn(0, MAX_ESCALATION_LEVEL)
    }

    private fun clearAlerts(doseId: String) {
        transitionScheduler.cancelTimers(doseId)
        notifier.cancelDoseAlert(doseId)
    }

    private suspend fun medName(dose: DoseInstanceEntity): String =
        medicationDao.getById(dose.medicationId)?.name.orEmpty()

    private suspend fun isCritical(dose: DoseInstanceEntity): Boolean =
        medicationDao.getById(dose.medicationId)?.isCritical ?: false

    private suspend fun recordTransition(
        dose: DoseInstanceEntity,
        from: DoseState?,
        to: DoseState,
        cause: DoseTransitionCause,
        atEpochMs: Long,
    ) {
        doseTransitionDao.insert(
            DoseTransitionEntity(
                id = idProvider(),
                doseInstanceId = dose.id,
                medicationId = dose.medicationId,
                fromState = from?.name,
                toState = to.name,
                cause = cause.name,
                atEpochMs = atEpochMs,
            ),
        )
    }

    private fun requireLegal(from: DoseState, to: DoseState) {
        if (!DoseTransition.isLegal(from, to)) throw IllegalDoseTransitionException(from, to)
    }

    private fun startOfDayEpochMs(nowMs: Long): Long {
        val zone = zoneProvider()
        return Instant.ofEpochMilli(nowMs)
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    companion object {
        /** D3: 15-minute snooze interval. */
        const val SNOOZE_INTERVAL_MS = 15L * 60L * 1000L

        /** Re-alert cadence; intensity rises each tick until acted on or the window closes. */
        const val ESCALATION_INTERVAL_MS = 5L * 60L * 1000L

        /** Cap on escalation intensity passed to the notifier. */
        const val MAX_ESCALATION_LEVEL = 3
    }
}

/** Outcome of a PRN log (spec §2.7). The warning is advisory and non-blocking (Law 3). */
data class PrnLogResult(
    val doseNumber: Int,
    val dailyMax: Int?,
    val exceedsMax: Boolean,
)
