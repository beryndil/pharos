package com.beryndil.pharos.medication

import androidx.lifecycle.SavedStateHandle
import com.beryndil.pharos.data.drugref.dao.DrugSearchDao
import com.beryndil.pharos.data.drugref.dao.IngredientMapDao
import com.beryndil.pharos.data.drugref.entity.DrugSearchEntity
import com.beryndil.pharos.data.drugref.entity.IngredientMapEntity
import com.beryndil.pharos.data.medication.MedicationRepository
import com.beryndil.pharos.data.regimen.dao.DoseInstanceDao
import com.beryndil.pharos.data.regimen.dao.DoseTransitionDao
import com.beryndil.pharos.data.regimen.dao.MedicationDao
import com.beryndil.pharos.data.regimen.dao.RefillRecordDao
import com.beryndil.pharos.data.regimen.dao.ScheduleDao
import com.beryndil.pharos.data.regimen.dao.SchedulePhaseDao
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.DoseTransitionEntity
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.data.regimen.entity.RefillRecordEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.SchedulePhaseEntity
import com.beryndil.pharos.data.schedule.ScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the lazy DND permission request (A1 — Critical Alerts §4).
 *
 * Pure-JVM test: uses injected lambdas and minimal DAO fakes instead of Room or Android context.
 * The [AddEditMedicationViewModel.fetchCriticalMeds] lambda is injected directly so no Room DB
 * is needed — the test controls exactly which "critical meds" the VM sees.
 *
 * The DND access request must be triggered ONLY when:
 *  1. The user marks a medication as critical (isCritical = true)
 *  2. No other critical active medication already exists
 *  3. DND policy access is not already granted
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LazyDndPermissionTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun baseMed(id: String, isCritical: Boolean) = MedicationEntity(
        id = id, name = "Med-$id", rxcui = null, ingredientsJson = "[]",
        strength = "10 mg", form = MedicationForm.TABLET.name, doseAmount = "1 tablet",
        prescriber = null, pharmacy = null, purpose = null, isFreeText = false,
        isCritical = isCritical, status = MedicationStatus.ACTIVE.name,
        startEpochMs = 0L, endEpochMs = null, createdAtEpochMs = 0L, updatedAtEpochMs = 0L,
    )

    /**
     * Build a VM with injectable [isDndAccessGranted] and [existingCriticalMeds].
     * Uses [fetchCriticalMeds] lambda injection — no Room DB needed (pure-JVM).
     */
    private fun makeVm(
        isDndAccessGranted: Boolean = false,
        existingCriticalMeds: List<MedicationEntity> = emptyList(),
        editMedId: String? = null,
    ): AddEditMedicationViewModel {
        val repo = MedicationRepository(
            medicationDao = NoOpMedicationDao(),
            drugSearchDao = NoOpDrugSearchDao(),
            ingredientMapDao = NoOpIngredientMapDao(),
            doseTransitionDao = NoOpDoseTransitionDao(),
            doseInstanceDao = NoOpDoseInstanceDao(),
            schedulePhaseDao = NoOpSchedulePhaseDao(),
            scheduleDao = NoOpScheduleDao(),
            refillRecordDao = NoOpRefillRecordDao(),
        )
        val scheduleRepo = ScheduleRepository(
            scheduleDao = NoOpScheduleDao(),
            schedulePhaseDao = NoOpSchedulePhaseDao(),
            doseInstanceDao = NoOpDoseInstanceDao(),
        )
        val savedState = if (editMedId != null) SavedStateHandle(mapOf("medId" to editMedId))
                         else SavedStateHandle()
        return AddEditMedicationViewModel(
            repository = repo,
            scheduleRepository = scheduleRepo,
            savedStateHandle = savedState,
            ioDispatcher = testDispatcher,
            isDndAccessGranted = { isDndAccessGranted },
            fetchCriticalMeds = { existingCriticalMeds },
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun firstCriticalMed_dndNotGranted_showsRationale() = runTest {
        val vm = makeVm(isDndAccessGranted = false, existingCriticalMeds = emptyList())
        vm.onEvent(AddEditMedEvent.IsCriticalToggled(true))
        advanceUntilIdle()
        assertTrue(
            "Toggling first critical med with DND not granted must show rationale",
            vm.uiState.value.showDndPermissionRationale,
        )
    }

    @Test
    fun firstCriticalMed_dndAlreadyGranted_noRationale() = runTest {
        val vm = makeVm(isDndAccessGranted = true, existingCriticalMeds = emptyList())
        vm.onEvent(AddEditMedEvent.IsCriticalToggled(true))
        advanceUntilIdle()
        assertFalse(
            "When DND is already granted, no rationale dialog needed",
            vm.uiState.value.showDndPermissionRationale,
        )
    }

    @Test
    fun secondCriticalMed_dndNotGranted_noRationale() = runTest {
        val existing = listOf(baseMed("existing", isCritical = true))
        val vm = makeVm(isDndAccessGranted = false, existingCriticalMeds = existing)
        vm.onEvent(AddEditMedEvent.IsCriticalToggled(true))
        advanceUntilIdle()
        assertFalse(
            "When another critical med already exists, must NOT re-prompt for DND access",
            vm.uiState.value.showDndPermissionRationale,
        )
    }

    @Test
    fun editingExistingCriticalMed_selfExcluded_showsRationale() = runTest {
        val selfMed = baseMed("m1", isCritical = true)
        val vm = makeVm(
            isDndAccessGranted = false,
            existingCriticalMeds = listOf(selfMed),
            editMedId = "m1",
        )
        vm.onEvent(AddEditMedEvent.IsCriticalToggled(true))
        advanceUntilIdle()
        assertTrue(
            "Editing the only critical med (self-excluded) must still show rationale",
            vm.uiState.value.showDndPermissionRationale,
        )
    }

    @Test
    fun togglingCriticalOff_noRationale() = runTest {
        val vm = makeVm(isDndAccessGranted = false)
        vm.onEvent(AddEditMedEvent.IsCriticalToggled(false))
        advanceUntilIdle()
        assertFalse(
            "Toggling isCritical to false must never show the DND rationale",
            vm.uiState.value.showDndPermissionRationale,
        )
    }

    @Test
    fun rationaleDismissed_clearsFlag() = runTest {
        val vm = makeVm(isDndAccessGranted = false, existingCriticalMeds = emptyList())
        vm.onEvent(AddEditMedEvent.IsCriticalToggled(true))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showDndPermissionRationale)

        vm.onEvent(AddEditMedEvent.DndPermissionRationaleDismissed)
        advanceUntilIdle()
        assertFalse(
            "Dismissing the rationale dialog must clear showDndPermissionRationale",
            vm.uiState.value.showDndPermissionRationale,
        )
    }
}

// ── Minimal no-op DAO fakes (pure Kotlin, no Room, no Android context) ─────────────────────────

private class NoOpMedicationDao : MedicationDao {
    private val empty = MutableStateFlow<List<MedicationEntity>>(emptyList())
    override suspend fun insert(medication: MedicationEntity) = Unit
    override suspend fun update(medication: MedicationEntity) = Unit
    override suspend fun getById(id: String): MedicationEntity? = null
    override fun observeActive(): Flow<List<MedicationEntity>> = empty.asStateFlow()
    override fun observeAll(): Flow<List<MedicationEntity>> = empty.asStateFlow()
    override suspend fun getActiveOnce(): List<MedicationEntity> = emptyList()
    override suspend fun getAll(): List<MedicationEntity> = emptyList()
    override suspend fun getCriticalActive(): List<MedicationEntity> = emptyList()
    override suspend fun countNonEnded(): Int = 0
    override suspend fun deleteById(id: String) = Unit
    override suspend fun clearSubstituteRef(medId: String) = Unit
}

private class NoOpDrugSearchDao : DrugSearchDao {
    override suspend fun insertAll(drugs: List<DrugSearchEntity>) = Unit
    override suspend fun searchByName(q: String): List<DrugSearchEntity> = emptyList()
    override suspend fun getByRxcui(rxcui: String): DrugSearchEntity? = null
    override suspend fun firstBrandNameForIngredient(ingredientRxcui: String): String? = null
    override suspend fun allBrandNamesForIngredients(ingredientRxcuis: List<String>): List<String> = emptyList()
    override suspend fun count(): Int = 0
}

private class NoOpIngredientMapDao : IngredientMapDao {
    override suspend fun insertAll(edges: List<IngredientMapEntity>) = Unit
    override suspend fun ingredientsForDrug(drugRxcui: String): List<IngredientMapEntity> = emptyList()
    override suspend fun getForDrugs(drugRxcuis: List<String>): List<IngredientMapEntity> = emptyList()
    override suspend fun getByIngredientRxcuis(rxcuis: List<String>): List<IngredientMapEntity> = emptyList()
    override suspend fun count(): Int = 0
}

private class NoOpScheduleDao : ScheduleDao {
    private val empty = MutableStateFlow<List<ScheduleEntity>>(emptyList())
    override suspend fun insert(schedule: ScheduleEntity) = Unit
    override suspend fun deactivate(id: String) = Unit
    override fun observeActiveByMedication(medicationId: String): Flow<List<ScheduleEntity>> = empty.asStateFlow()
    override suspend fun getActiveByMedicationOnce(medicationId: String): List<ScheduleEntity> = emptyList()
    override suspend fun getById(id: String): ScheduleEntity? = null
    override suspend fun getAllVersionsForMedication(medicationId: String): List<ScheduleEntity> = emptyList()
    override suspend fun getAll(): List<ScheduleEntity> = emptyList()
    override fun observeAllActivePrn(): Flow<List<ScheduleEntity>> = empty.asStateFlow()
    override suspend fun deleteByMedication(medicationId: String) = Unit
}

private class NoOpSchedulePhaseDao : SchedulePhaseDao {
    override suspend fun insertAll(phases: List<SchedulePhaseEntity>) = Unit
    override suspend fun getPhasesForSchedule(scheduleId: String): List<SchedulePhaseEntity> = emptyList()
    override suspend fun getAll(): List<SchedulePhaseEntity> = emptyList()
    override suspend fun deleteByMedication(medicationId: String) = Unit
}

private class NoOpDoseInstanceDao : DoseInstanceDao {
    private val empty = MutableStateFlow<List<DoseInstanceEntity>>(emptyList())
    override suspend fun insert(dose: DoseInstanceEntity) = Unit
    override suspend fun insertAll(doses: List<DoseInstanceEntity>) = Unit
    override suspend fun markDue(id: String) = Unit
    override suspend fun markTaken(id: String, takenEpochMs: Long) = Unit
    override suspend fun markSnoozed(id: String, snoozeUntilEpochMs: Long) = Unit
    override suspend fun markSkipped(id: String, skippedEpochMs: Long) = Unit
    override suspend fun markDueFromSnooze(id: String) = Unit
    override suspend fun markMissed(id: String, missedEpochMs: Long) = Unit
    override suspend fun cancelScheduledBySchedule(scheduleId: String, missedEpochMs: Long) = Unit
    override suspend fun cancelOrphanedScheduled(nowMs: Long): Int = 0
    override suspend fun getAllInWindow(from: Long, to: Long): List<DoseInstanceEntity> = emptyList()
    override suspend fun getById(id: String): DoseInstanceEntity? = null
    override fun observeByMedication(medicationId: String): Flow<List<DoseInstanceEntity>> = empty.asStateFlow()
    override fun observePending(): Flow<List<DoseInstanceEntity>> = empty.asStateFlow()
    override suspend fun getNextScheduled(medicationId: String): DoseInstanceEntity? = null
    override suspend fun getNextScheduledAfter(medicationId: String, afterEpochMs: Long): DoseInstanceEntity? = null
    override fun observeActionable(scheduledFromEpochMs: Long, beforeEpochMs: Long): Flow<List<DoseInstanceEntity>> = empty.asStateFlow()
    override suspend fun getAllScheduledBefore(beforeEpochMs: Long): List<DoseInstanceEntity> = emptyList()
    override suspend fun countTakenSince(medicationId: String, sinceEpochMs: Long): Int = 0
    override suspend fun getEarliestScheduled(): DoseInstanceEntity? = null
    override suspend fun countById(id: String): Int = 0
    override suspend fun getDueTimesForSchedule(scheduleId: String): List<Long> = emptyList()
    override suspend fun getLastTakenForSchedule(scheduleId: String): DoseInstanceEntity? = null
    override suspend fun getAll(): List<DoseInstanceEntity> = emptyList()
    override suspend fun deleteById(id: String) = Unit
    override fun observeAllTakenSince(sinceEpochMs: Long): Flow<List<DoseInstanceEntity>> = empty.asStateFlow()
    override suspend fun deleteByMedication(medicationId: String) = Unit
}

private class NoOpDoseTransitionDao : DoseTransitionDao {
    private val empty = MutableStateFlow<List<DoseTransitionEntity>>(emptyList())
    override suspend fun insert(transition: DoseTransitionEntity) = Unit
    override fun observeByMedication(medicationId: String): Flow<List<DoseTransitionEntity>> = empty.asStateFlow()
    override suspend fun getByDose(doseInstanceId: String): List<DoseTransitionEntity> = emptyList()
    override suspend fun countByDose(doseInstanceId: String): Int = 0
    override suspend fun getAll(): List<DoseTransitionEntity> = emptyList()
    override suspend fun deleteByMedication(medicationId: String) = Unit
    override suspend fun deleteByDoseInstance(doseInstanceId: String) = Unit
}

private class NoOpRefillRecordDao : RefillRecordDao {
    private val empty = MutableStateFlow<List<RefillRecordEntity>>(emptyList())
    override suspend fun insert(record: RefillRecordEntity) = Unit
    override fun observeByMedication(medicationId: String): Flow<List<RefillRecordEntity>> = empty.asStateFlow()
    override suspend fun getLatest(medicationId: String): RefillRecordEntity? = null
    override suspend fun getAll(): List<RefillRecordEntity> = emptyList()
    override suspend fun deleteByMedication(medicationId: String) = Unit
}
