package com.beryndil.pharos.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Unit tests for [AndroidAlarmScheduler] using Robolectric's [ShadowAlarmManager] (Standards §10).
 *
 * These assert the exact AlarmManager API chosen and the exact trigger time — the risk core of
 * the alarm engine (spec §3.4). API 34 is pinned so the exact-alarm permission path is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var shadow: ShadowAlarmManager
    private lateinit var scheduler: AndroidAlarmScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadow = shadowOf(alarmManager)
        scheduler = AndroidAlarmScheduler(context)
    }

    @Test
    fun doseAlarm_whenExactAllowed_usesSetAlarmClockAtExactTrigger() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        val trigger = 1_900_000_000_000L
        val mode = scheduler.schedule(AlarmRequest(AlarmKind.DOSE, trigger, doseId = "d1"))

        assertEquals(AlarmMode.EXACT, mode)
        val scheduled = shadow.peekNextScheduledAlarm()
        assertNotNull("a dose alarm must be scheduled", scheduled)
        assertEquals(trigger, scheduled!!.triggerAtTime)
        assertEquals("must not be a repeating alarm", 0L, scheduled.interval)
        // setAlarmClock records an AlarmClockInfo retrievable via getNextAlarmClock().
        assertNotNull("setAlarmClock must be used for exact dose alarms", alarmManager.nextAlarmClock)
        assertEquals(trigger, alarmManager.nextAlarmClock!!.triggerTime)
    }

    @Test
    fun doseAlarm_whenExactDenied_fallsBackToSetWindow_neverDrops() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val trigger = 1_900_000_500_000L
        val mode = scheduler.schedule(AlarmRequest(AlarmKind.DOSE, trigger, doseId = "d1"))

        assertEquals(AlarmMode.WINDOWED_FALLBACK, mode)
        val scheduled = shadow.peekNextScheduledAlarm()
        assertNotNull("fallback must still schedule an alarm — never drop the reminder", scheduled)
        assertEquals(trigger, scheduled!!.triggerAtTime)
        assertNull("windowed fallback must not use setAlarmClock", alarmManager.nextAlarmClock)
    }

    @Test
    fun dailyRollover_whenExactAllowed_usesExactAndAllowWhileIdle_notSetAlarmClock() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        val trigger = 1_900_100_000_000L
        val mode = scheduler.schedule(AlarmRequest(AlarmKind.DAILY_ROLLOVER, trigger))

        assertEquals(AlarmMode.EXACT, mode)
        val scheduled = shadow.peekNextScheduledAlarm()
        assertNotNull(scheduled)
        assertEquals(trigger, scheduled!!.triggerAtTime)
        // Maintenance alarms must NOT consume the status-bar alarm-clock affordance.
        assertNull("rollover must not use setAlarmClock", alarmManager.nextAlarmClock)
    }

    @Test
    fun reschedulingSameKind_replacesPriorAlarm_singleSlot() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        scheduler.schedule(AlarmRequest(AlarmKind.DOSE, 1_900_000_000_000L, doseId = "d1"))
        scheduler.schedule(AlarmRequest(AlarmKind.DOSE, 1_900_000_900_000L, doseId = "d2"))

        // FLAG_UPDATE_CURRENT + a stable request code means the second schedule replaces the first.
        val doseAlarms = shadow.scheduledAlarms.filter { it.triggerAtTime != 0L }
        assertEquals("single-fire-and-reschedule keeps one dose slot", 1, doseAlarms.size)
        assertEquals(1_900_000_900_000L, doseAlarms.first().triggerAtTime)
    }

    @Test
    fun cancel_removesPendingAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.schedule(AlarmRequest(AlarmKind.DOSE, 1_900_000_000_000L, doseId = "d1"))
        assertNotNull(shadow.peekNextScheduledAlarm())

        scheduler.cancel(AlarmKind.DOSE)

        assertTrue("alarm must be cancelled", shadow.scheduledAlarms.isEmpty())
    }

    @Test
    fun canScheduleExact_reflectsShadowState() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(scheduler.canScheduleExact())
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(false, scheduler.canScheduleExact())
    }
}
