package com.beryndil.pharos.reliability

import com.beryndil.pharos.alarm.SettingsReliabilityLog
import com.beryndil.pharos.data.regimen.dao.MedicationDao
import com.beryndil.pharos.data.regimen.dao.SettingDao
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
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
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for transparent DND-access degrade (A1 — Critical Alerts §3.4, Law 6).
 *
 * When DND policy access is NOT granted and the user has critical medications, the reliability
 * dashboard must:
 *  - Report [ItemStatus.RISKY] for the dndAccess item
 *  - Expose a [FixAction.DndPolicySettings] action so the user can grant access
 *
 * When DND access IS granted (or no critical meds exist), the item is [ItemStatus.OK].
 *
 * All permission checks are replaced by injected lambdas — no Android context needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DndDegradeTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────

    private fun makeMed(id: String, isCritical: Boolean) = MedicationEntity(
        id = id,
        name = "Med-$id",
        rxcui = null,
        ingredientsJson = "[]",
        strength = "10 mg",
        form = "TABLET",
        doseAmount = "1 tablet",
        prescriber = null,
        pharmacy = null,
        purpose = null,
        isFreeText = false,
        isCritical = isCritical,
        status = MedicationStatus.ACTIVE.name,
        startEpochMs = 0L,
        endEpochMs = null,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    private fun makeVm(
        meds: List<MedicationEntity> = emptyList(),
        isDndAccessGranted: Boolean = false,
    ): ReliabilityDashboardViewModel {
        val medDao = FakeMedicationDao(meds)
        return ReliabilityDashboardViewModel(
            settingDao = FakeSettingDao2(),
            medicationDao = medDao,
            canScheduleExact = { true },
            isIgnoringBatteryOpt = { true },
            isNotificationGranted = { true },
            canUseFullScreenIntent = { true },
            isDndAccessGranted = { isDndAccessGranted },
            oemName = "Google",
        )
    }

    private suspend fun stateOf(vm: ReliabilityDashboardViewModel) = vm.uiState.first { true }

    // ── Tests ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun noCriticalMeds_dndItem_isOk_regardlessOfAccess() = runTest {
        val state = stateOf(makeVm(meds = emptyList(), isDndAccessGranted = false))
        assertEquals(
            "No critical meds → DND item should be OK (access is irrelevant)",
            ItemStatus.OK,
            state.dndAccess.status,
        )
    }

    @Test
    fun criticalMeds_dndNotGranted_showsRisky() = runTest {
        val state = stateOf(makeVm(meds = listOf(makeMed("m1", isCritical = true)), isDndAccessGranted = false))
        assertEquals(
            "Critical med + DND denied → dndAccess must be RISKY (spec §3.4, Law 6)",
            ItemStatus.RISKY,
            state.dndAccess.status,
        )
        assertNotNull(
            "Risky DND item must expose a fix action",
            state.dndAccess.fixAction,
        )
        assertEquals(
            "Fix action must route to DND policy settings",
            FixAction.DndPolicySettings,
            state.dndAccess.fixAction,
        )
    }

    @Test
    fun criticalMeds_dndGranted_showsOk() = runTest {
        val state = stateOf(makeVm(meds = listOf(makeMed("m1", isCritical = true)), isDndAccessGranted = true))
        assertEquals(
            "Critical med + DND granted → dndAccess must be OK",
            ItemStatus.OK,
            state.dndAccess.status,
        )
    }

    @Test
    fun criticalMedNames_areListedInState() = runTest {
        val meds = listOf(
            makeMed("a", isCritical = true),
            makeMed("b", isCritical = false),
            makeMed("c", isCritical = true),
        )
        val state = stateOf(makeVm(meds = meds, isDndAccessGranted = true))
        assertEquals(
            "Only critical med names must appear in criticalMedNames",
            listOf("Med-a", "Med-c"),
            state.criticalMedNames,
        )
    }
}

// ── Fakes ─────────────────────────────────────────────────────────────────────────────────────

private class FakeSettingDao2 : SettingDao {
    private val _all = MutableStateFlow<List<SettingEntity>>(emptyList())
    override suspend fun upsert(setting: SettingEntity) { /* no-op */ }
    override suspend fun get(key: String): SettingEntity? = null
    override fun observeAll(): Flow<List<SettingEntity>> = _all.asStateFlow()
    override suspend fun getAll(): List<SettingEntity> = emptyList()
}

private class FakeMedicationDao(private val meds: List<MedicationEntity>) : MedicationDao {
    private val _flow = MutableStateFlow(meds)
    override suspend fun insert(medication: MedicationEntity) = Unit
    override suspend fun update(medication: MedicationEntity) = Unit
    override suspend fun getById(id: String): MedicationEntity? = meds.find { it.id == id }
    override fun observeActive(): Flow<List<MedicationEntity>> = _flow.asStateFlow()
    override fun observeAll(): Flow<List<MedicationEntity>> = _flow.asStateFlow()
    override suspend fun getActiveOnce(): List<MedicationEntity> = meds
    override suspend fun getAll(): List<MedicationEntity> = meds
    override suspend fun getCriticalActive(): List<MedicationEntity> =
        meds.filter { it.isCritical && it.status != "ENDED" }
}
