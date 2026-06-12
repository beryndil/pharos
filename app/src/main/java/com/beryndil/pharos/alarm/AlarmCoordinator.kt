package com.beryndil.pharos.alarm

import com.beryndil.pharos.data.regimen.dao.DoseInstanceDao
import com.beryndil.pharos.data.regimen.dao.MedicationDao
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.data.schedule.ScheduleRepository
import java.time.Instant
import java.time.ZoneId

/**
 * The brain of the alarm engine (spec §3.4). It owns the single-fire-and-reschedule loop and is
 * the single place that decides *which* dose alarm is pending and *when* to recompute it.
 *
 * Design (Standards §3, §10):
 *  - **Single pending dose alarm.** [rearmNextDoseAlarm] schedules exactly one exact alarm for
 *    the earliest SCHEDULED dose across all meds. On fire ([onDoseAlarmFired]) the dose is marked
 *    DUE so it drops out of the query, then the next is scheduled. Never `setRepeating`.
 *  - **Re-registration** ([onReRegistration]) recomputes the pending alarm from the DB after
 *    BOOT_COMPLETED / TIME_SET / TIMEZONE_CHANGED / package-replace / exact-alarm-permission
 *    change. Because dose instants are stored as UTC epoch-ms derived through `DoseClock`, a
 *    timezone or DST change is handled simply by re-reading the stored instants.
 *  - **Daily rollover** ([onDailyRollover]) tops up the generated dose horizon and re-anchors —
 *    the DST/midnight safety net (a wrong-hour dose after a DST shift is a safety bug, §3.4).
 *
 * All methods are `suspend` and must run off the main thread (the receivers use `goAsync`).
 * [now] and [zoneProvider] are injectable so tests can pin the clock and zone (Standards §0, §2).
 */
class AlarmCoordinator(
    private val scheduler: AlarmScheduler,
    private val notifier: DoseNotifier,
    private val doseInstanceDao: DoseInstanceDao,
    private val medicationDao: MedicationDao,
    private val scheduleRepository: ScheduleRepository,
    private val reliabilityLog: ReliabilityLog,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val doseDueListener: DoseDueListener = DoseDueListener.NoOp,
) {

    /** Result of a re-arm, for tests and the reliability log. */
    data class RearmResult(val doseId: String?, val triggerAtEpochMs: Long?, val mode: AlarmMode?)

    /**
     * Schedule (or cancel) the single pending dose alarm for the earliest SCHEDULED dose.
     *
     * A dose whose due time is already in the past (device was off, app updating) is still
     * scheduled — AlarmManager fires a past trigger immediately, so reboot recovery alerts the
     * overdue dose rather than dropping it.
     */
    suspend fun rearmNextDoseAlarm(): RearmResult {
        val next = doseInstanceDao.getEarliestScheduled()
        if (next == null) {
            scheduler.cancel(AlarmKind.DOSE)
            reliabilityLog.recordNoUpcomingAlarm(now())
            return RearmResult(null, null, null)
        }
        val medName = medicationDao.getById(next.medicationId)?.name
        val mode = scheduler.schedule(
            AlarmRequest(
                kind = AlarmKind.DOSE,
                triggerAtEpochMs = next.dueEpochMs,
                doseId = next.id,
                medName = medName,
                dueEpochMs = next.dueEpochMs,
            ),
        )
        reliabilityLog.recordAlarmScheduled(AlarmKind.DOSE, mode, next.dueEpochMs)
        return RearmResult(next.id, next.dueEpochMs, mode)
    }

    /**
     * Handle a fired dose alarm: mark the dose DUE (the alarm-firing transition; the full
     * DUE→TAKEN/SNOOZED/SKIPPED/MISSED machine is Slice 5), post the full-screen alert, then
     * schedule the next dose. Idempotent: a re-delivered alarm re-posts and re-arms safely.
     */
    suspend fun onDoseAlarmFired(doseId: String) {
        val firedAt = now()
        val dose = doseInstanceDao.getById(doseId)
        if (dose != null && dose.state == DoseState.SCHEDULED.name) {
            doseInstanceDao.markDue(doseId)
        }
        val med = dose?.let { medicationDao.getById(it.medicationId) }
        val medName = med?.name.orEmpty()
        val isCritical = med?.isCritical ?: false
        notifier.postDoseDueAlert(doseId, medName, dose?.dueEpochMs ?: firedAt, 0, isCritical)
        reliabilityLog.recordAlarmFired(AlarmKind.DOSE, firedAt)
        rearmNextDoseAlarm()
        // Hand off to the dose state machine (Slice 5): arm the D2 miss-window deadline and the
        // escalation re-alerts. NoOp in a bare Slice 4 wiring.
        doseDueListener.onEnteredDue(doseId, firedAt)
    }

    /**
     * Handle a system re-registration trigger (boot, time/timezone change, package replace,
     * exact-alarm permission change). Recompute the pending dose alarm and re-anchor the rollover.
     */
    suspend fun onReRegistration(trigger: String) {
        reliabilityLog.recordReRegistration(trigger, now())
        rearmNextDoseAlarm()
        scheduleDailyRollover()
    }

    /**
     * The daily DST/midnight re-anchor. Extends the generated dose horizon for active meds, then
     * re-arms the pending alarm and schedules the next rollover.
     */
    suspend fun onDailyRollover() {
        topUpGeneration()
        rearmNextDoseAlarm()
        scheduleDailyRollover()
    }

    /** Schedule a near-immediate test reminder through the SAME engine (Law 6). */
    suspend fun scheduleTestReminder(delayMs: Long = DEFAULT_TEST_DELAY_MS) {
        val triggerAt = now() + delayMs
        val mode = scheduler.schedule(AlarmRequest(AlarmKind.TEST, triggerAt))
        reliabilityLog.recordAlarmScheduled(AlarmKind.TEST, mode, triggerAt)
    }

    /** Post the test reminder alert (called by [AlarmReceiver] when the test alarm fires). */
    fun fireTestReminder() {
        notifier.postTestReminder()
    }

    /** Schedule the next daily rollover at the upcoming local midnight in the current zone. */
    suspend fun scheduleDailyRollover() {
        val zone = zoneProvider()
        val nowInstant = Instant.ofEpochMilli(now())
        val nextMidnight = nowInstant.atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val mode = scheduler.schedule(AlarmRequest(AlarmKind.DAILY_ROLLOVER, nextMidnight))
        reliabilityLog.recordAlarmScheduled(AlarmKind.DAILY_ROLLOVER, mode, nextMidnight)
    }

    private suspend fun topUpGeneration() {
        val from = Instant.ofEpochMilli(now())
        val to = from.plusMillis(GENERATION_HORIZON_DAYS * MS_PER_DAY)
        for (med in medicationDao.getActiveOnce()) {
            scheduleRepository.generateInstancesForMed(med.id, from, to)
        }
    }

    companion object {
        const val DEFAULT_TEST_DELAY_MS = 5_000L
        private const val GENERATION_HORIZON_DAYS = 90L
        private const val MS_PER_DAY = 86_400_000L
    }
}
