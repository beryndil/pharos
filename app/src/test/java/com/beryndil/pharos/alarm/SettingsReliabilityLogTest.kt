package com.beryndil.pharos.alarm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.data.regimen.RegimenDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Verifies [SettingsReliabilityLog] persists the facts the Slice 6 dashboard will read. */
@RunWith(RobolectricTestRunner::class)
class SettingsReliabilityLogTest {

    private lateinit var db: RegimenDatabase
    private lateinit var log: SettingsReliabilityLog

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RegimenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        log = SettingsReliabilityLog(db.settingDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun recordAlarmScheduled_persistsModeAndTrigger() = runTest {
        log.recordAlarmScheduled(AlarmKind.DOSE, AlarmMode.WINDOWED_FALLBACK, 1_900_000_000_000L)

        assertEquals(
            AlarmMode.WINDOWED_FALLBACK.name,
            db.settingDao().get(SettingsReliabilityLog.KEY_ALARM_MODE)!!.value,
        )
        assertEquals(
            "1900000000000",
            db.settingDao().get(SettingsReliabilityLog.KEY_NEXT_ALARM_AT)!!.value,
        )
        assertEquals(
            AlarmKind.DOSE.name,
            db.settingDao().get(SettingsReliabilityLog.KEY_NEXT_ALARM_KIND)!!.value,
        )
    }

    @Test
    fun recordNoUpcomingAlarm_marksNone() = runTest {
        log.recordNoUpcomingAlarm(1_900_000_000_000L)
        assertEquals(
            SettingsReliabilityLog.VALUE_NONE,
            db.settingDao().get(SettingsReliabilityLog.KEY_NEXT_ALARM_AT)!!.value,
        )
    }

    @Test
    fun recordReRegistration_persistsTrigger() = runTest {
        log.recordReRegistration("android.intent.action.BOOT_COMPLETED", 1_900_000_000_000L)
        assertEquals(
            "android.intent.action.BOOT_COMPLETED",
            db.settingDao().get(SettingsReliabilityLog.KEY_LAST_REREGISTRATION)!!.value,
        )
    }
}
