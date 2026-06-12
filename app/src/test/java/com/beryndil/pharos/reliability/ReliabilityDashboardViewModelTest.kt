package com.beryndil.pharos.reliability

import com.beryndil.pharos.alarm.AlarmMode
import com.beryndil.pharos.alarm.SettingsReliabilityLog
import com.beryndil.pharos.data.regimen.dao.SettingDao
import com.beryndil.pharos.data.regimen.entity.SettingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ReliabilityDashboardViewModel] (spec §2.13, Law 6).
 *
 * All permission checks are replaced by injected lambdas so tests run on the JVM without any
 * Android context. The [SettingDao] is replaced by an in-memory fake that supports reactive
 * updates via [MutableStateFlow].
 *
 * Test coverage:
 *  - Each permission/setting → OK vs RISKY status mapping
 *  - Risky items expose the correct [FixAction] (non-null)
 *  - OK items have no fix action (null)
 *  - last-alarm / next-alarm / alarm-mode derived from reliability log settings
 *  - boot-receiver health from log settings
 *  - drug-DB version / updated-at from setting keys (Slice 8 will write these)
 *  - OEM detection: auto-start RISKY for Xiaomi/Oppo/vivo/Honor, OK otherwise
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReliabilityDashboardViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private fun makeVm(
        settingDao: SettingDao = FakeSettingDao(),
        canScheduleExact: Boolean = true,
        isIgnoringBatteryOpt: Boolean = true,
        isNotificationGranted: Boolean = true,
        canUseFullScreenIntent: Boolean = true,
        oemName: String = "Google",
    ) = ReliabilityDashboardViewModel(
        settingDao = settingDao,
        canScheduleExact = { canScheduleExact },
        isIgnoringBatteryOpt = { isIgnoringBatteryOpt },
        isNotificationGranted = { isNotificationGranted },
        canUseFullScreenIntent = { canUseFullScreenIntent },
        oemName = oemName,
    )

    private suspend fun stateOf(vm: ReliabilityDashboardViewModel) =
        vm.uiState.first { true }

    // ── Exact alarm mapping ───────────────────────────────────────────────────────────────────

    @Test
    fun exactAlarm_ok_whenCanScheduleExact() = runTest {
        val state = stateOf(makeVm(canScheduleExact = true))
        assertEquals(ItemStatus.OK, state.exactAlarm.status)
        assertNull(state.exactAlarm.fixAction)
    }

    @Test
    fun exactAlarm_risky_whenCannotScheduleExact() = runTest {
        val state = stateOf(makeVm(canScheduleExact = false))
        assertEquals(ItemStatus.RISKY, state.exactAlarm.status)
        assertEquals(FixAction.ExactAlarmSettings, state.exactAlarm.fixAction)
    }

    // ── Battery optimization mapping ──────────────────────────────────────────────────────────

    @Test
    fun battery_ok_whenIgnoringBatteryOpt() = runTest {
        val state = stateOf(makeVm(isIgnoringBatteryOpt = true))
        assertEquals(ItemStatus.OK, state.batteryOptimization.status)
        assertNull(state.batteryOptimization.fixAction)
    }

    @Test
    fun battery_risky_whenNotIgnoringBatteryOpt() = runTest {
        val state = stateOf(makeVm(isIgnoringBatteryOpt = false))
        assertEquals(ItemStatus.RISKY, state.batteryOptimization.status)
        assertEquals(FixAction.BatterySettings, state.batteryOptimization.fixAction)
    }

    // ── Notification mapping ──────────────────────────────────────────────────────────────────

    @Test
    fun notification_ok_whenGranted() = runTest {
        val state = stateOf(makeVm(isNotificationGranted = true))
        assertEquals(ItemStatus.OK, state.notification.status)
        assertNull(state.notification.fixAction)
    }

    @Test
    fun notification_risky_whenDenied() = runTest {
        val state = stateOf(makeVm(isNotificationGranted = false))
        assertEquals(ItemStatus.RISKY, state.notification.status)
        assertEquals(FixAction.NotificationSettings, state.notification.fixAction)
    }

    // ── Full-screen intent mapping ────────────────────────────────────────────────────────────

    @Test
    fun fullScreen_ok_whenAvailable() = runTest {
        val state = stateOf(makeVm(canUseFullScreenIntent = true))
        assertEquals(ItemStatus.OK, state.fullScreenIntent.status)
        assertNull(state.fullScreenIntent.fixAction)
    }

    @Test
    fun fullScreen_risky_whenUnavailable() = runTest {
        val state = stateOf(makeVm(canUseFullScreenIntent = false))
        assertEquals(ItemStatus.RISKY, state.fullScreenIntent.status)
        assertEquals(FixAction.FullScreenIntentSettings, state.fullScreenIntent.fixAction)
    }

    // ── OEM auto-start mapping ────────────────────────────────────────────────────────────────

    @Test
    fun autoStart_risky_forKillerOems() = runTest {
        for (oem in listOf("Xiaomi", "xiaomi", "OPPO", "oppo", "vivo", "VIVO", "Honor", "HONOR")) {
            val state = stateOf(makeVm(oemName = oem))
            assertEquals("Expected RISKY for OEM=$oem", ItemStatus.RISKY, state.backgroundAutoStart.status)
            assertNotNull("Expected fix action for OEM=$oem", state.backgroundAutoStart.fixAction)
            // Fix action for OEM killer is the dontkillmyapp.com URL
            assertTrue(
                "Expected OpenUrl fix action for OEM=$oem",
                state.backgroundAutoStart.fixAction is FixAction.OpenUrl,
            )
        }
    }

    @Test
    fun autoStart_ok_forNonKillerOems() = runTest {
        for (oem in listOf("Google", "samsung", "motorola", "OnePlus", "")) {
            val state = stateOf(makeVm(oemName = oem))
            assertEquals("Expected OK for OEM=$oem", ItemStatus.OK, state.backgroundAutoStart.status)
            assertNull("Expected no fix action for OEM=$oem", state.backgroundAutoStart.fixAction)
        }
    }

    // ── Alarm log settings mapping ────────────────────────────────────────────────────────────

    @Test
    fun lastAlarmFired_parsedFromSettings() = runTest {
        val dao = FakeSettingDao()
        dao.upsert(SettingEntity(SettingsReliabilityLog.KEY_LAST_FIRED_AT, "1900000000000", 0L))
        val state = stateOf(makeVm(settingDao = dao))
        assertEquals(1_900_000_000_000L, state.lastAlarmFiredEpochMs)
    }

    @Test
    fun lastAlarmFired_nullWhenAbsent() = runTest {
        val state = stateOf(makeVm(settingDao = FakeSettingDao()))
        assertNull(state.lastAlarmFiredEpochMs)
    }

    @Test
    fun lastAlarmFired_nullWhenNone() = runTest {
        val dao = FakeSettingDao()
        dao.upsert(SettingEntity(SettingsReliabilityLog.KEY_LAST_FIRED_AT, "none", 0L))
        val state = stateOf(makeVm(settingDao = dao))
        assertNull(state.lastAlarmFiredEpochMs) // "none".toLongOrNull() == null
    }

    @Test
    fun nextAlarmEpoch_parsedFromSettings() = runTest {
        val dao = FakeSettingDao()
        dao.upsert(SettingEntity(SettingsReliabilityLog.KEY_NEXT_ALARM_AT, "1900000001000", 0L))
        val state = stateOf(makeVm(settingDao = dao))
        assertEquals(1_900_000_001_000L, state.nextAlarmEpochMs)
    }

    @Test
    fun nextAlarmEpoch_nullWhenNone() = runTest {
        val dao = FakeSettingDao()
        dao.upsert(SettingEntity(SettingsReliabilityLog.KEY_NEXT_ALARM_AT, "none", 0L))
        val state = stateOf(makeVm(settingDao = dao))
        assertNull(state.nextAlarmEpochMs)
    }

    @Test
    fun alarmMode_parsedExact() = runTest {
        val dao = FakeSettingDao()
        dao.upsert(SettingEntity(SettingsReliabilityLog.KEY_ALARM_MODE, AlarmMode.EXACT.name, 0L))
        val state = stateOf(makeVm(settingDao = dao))
        assertEquals(AlarmMode.EXACT, state.alarmMode)
    }

    @Test
    fun alarmMode_parsedWindowedFallback() = runTest {
        val dao = FakeSettingDao()
        dao.upsert(
            SettingEntity(
                SettingsReliabilityLog.KEY_ALARM_MODE,
                AlarmMode.WINDOWED_FALLBACK.name,
                0L,
            ),
        )
        val state = stateOf(makeVm(settingDao = dao))
        assertEquals(AlarmMode.WINDOWED_FALLBACK, state.alarmMode)
    }

    @Test
    fun alarmMode_nullWhenAbsent() = runTest {
        val state = stateOf(makeVm(settingDao = FakeSettingDao()))
        assertNull(state.alarmMode)
    }

    // ── Boot receiver health ──────────────────────────────────────────────────────────────────

    @Test
    fun bootReceiver_nullWhenNeverTriggered() = runTest {
        val state = stateOf(makeVm(settingDao = FakeSettingDao()))
        assertNull(state.bootReceiverLastTrigger)
    }

    @Test
    fun bootReceiver_presentWhenTriggered() = runTest {
        val dao = FakeSettingDao()
        val trigger = "android.intent.action.BOOT_COMPLETED"
        dao.upsert(SettingEntity(SettingsReliabilityLog.KEY_LAST_REREGISTRATION, trigger, 0L))
        dao.upsert(SettingEntity(SettingsReliabilityLog.KEY_LAST_REREGISTRATION_AT, "1900000000000", 0L))
        val state = stateOf(makeVm(settingDao = dao))
        assertEquals(trigger, state.bootReceiverLastTrigger)
        assertEquals(1_900_000_000_000L, state.bootReceiverLastTriggerEpochMs)
    }

    // ── Drug-DB version / updated-at (Slice 8 keys) ───────────────────────────────────────────

    @Test
    fun drugDbVersion_nullWhenAbsent() = runTest {
        val state = stateOf(makeVm(settingDao = FakeSettingDao()))
        assertNull(state.drugDbVersion) // composable shows "Bundled (local)" for null
    }

    @Test
    fun drugDbVersion_presentWhenWritten() = runTest {
        val dao = FakeSettingDao()
        dao.upsert(SettingEntity(ReliabilityDashboardViewModel.KEY_DRUGREF_VERSION, "2024-06", 0L))
        val state = stateOf(makeVm(settingDao = dao))
        assertEquals("2024-06", state.drugDbVersion)
    }

    @Test
    fun drugDbUpdatedAt_nullWhenAbsent() = runTest {
        val state = stateOf(makeVm(settingDao = FakeSettingDao()))
        assertNull(state.drugDbUpdatedAtEpochMs)
    }

    @Test
    fun drugDbUpdatedAt_parsedWhenPresent() = runTest {
        val dao = FakeSettingDao()
        dao.upsert(
            SettingEntity(ReliabilityDashboardViewModel.KEY_DRUGREF_UPDATED_AT, "1900000000000", 0L),
        )
        val state = stateOf(makeVm(settingDao = dao))
        assertEquals(1_900_000_000_000L, state.drugDbUpdatedAtEpochMs)
    }

    // ── All risky items expose a non-null fix action ──────────────────────────────────────────

    @Test
    fun riskyExactAlarm_hasFix() = runTest {
        val state = stateOf(makeVm(canScheduleExact = false))
        assertNotNull(state.exactAlarm.fixAction)
    }

    @Test
    fun riskyBattery_hasFix() = runTest {
        val state = stateOf(makeVm(isIgnoringBatteryOpt = false))
        assertNotNull(state.batteryOptimization.fixAction)
    }

    @Test
    fun riskyNotification_hasFix() = runTest {
        val state = stateOf(makeVm(isNotificationGranted = false))
        assertNotNull(state.notification.fixAction)
    }

    @Test
    fun riskyFullScreenIntent_hasFix() = runTest {
        val state = stateOf(makeVm(canUseFullScreenIntent = false))
        assertNotNull(state.fullScreenIntent.fixAction)
    }

    @Test
    fun riskyAutoStart_hasFix() = runTest {
        val state = stateOf(makeVm(oemName = "xiaomi"))
        assertNotNull(state.backgroundAutoStart.fixAction)
    }
}

// ── Fake ─────────────────────────────────────────────────────────────────────────────────────

/**
 * Reactive in-memory [SettingDao] substitute. Emits via [MutableStateFlow] so the ViewModel's
 * `observeAll()` collector picks up changes made during the test.
 */
private class FakeSettingDao : SettingDao {
    private val _all = MutableStateFlow<List<SettingEntity>>(emptyList())

    override suspend fun upsert(setting: SettingEntity) {
        val updated = _all.value.toMutableList()
        updated.removeIf { it.key == setting.key }
        updated.add(setting)
        _all.value = updated
    }

    override suspend fun get(key: String): SettingEntity? =
        _all.value.find { it.key == key }

    override fun observeAll(): Flow<List<SettingEntity>> = _all.asStateFlow()
}
