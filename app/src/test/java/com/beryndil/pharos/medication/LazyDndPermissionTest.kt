package com.beryndil.pharos.medication

import androidx.lifecycle.SavedStateHandle
import com.beryndil.pharos.data.drugref.dao.IngredientDao
import com.beryndil.pharos.data.drugref.dao.ProductDao
import com.beryndil.pharos.data.drugref.entity.IngredientEntity
import com.beryndil.pharos.data.drugref.entity.ProductEntity
import com.beryndil.pharos.data.medication.MedicationRepository
import com.beryndil.pharos.data.regimen.dao.DoseInstanceDao
import com.beryndil.pharos.data.regimen.dao.MedicationDao
import com.beryndil.pharos.data.regimen.dao.ScheduleDao
import com.beryndil.pharos.data.regimen.dao.SchedulePhaseDao
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
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
            productDao = NoOpProductDao(),
            ingredientDao = NoOpIngredientDao(),
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
            // Key injection: override the DB call with a pure lambda.
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
        // The med being edited ("m1") is in the existing list, but should be excluded from
        // "others" count — so it's treated as the first critical med → rationale shows.
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
}

private class NoOpProductDao : ProductDao {
    override suspend fun insertAll(products: List<ProductEntity>) = Unit
    override suspend fun getByRxcui(rxcui: String): ProductEntity? = null
    override suspend fun searchByName(query: String): List<ProductEntity> = emptyList()
    override suspend fun count(): Int = 0
    override suspend fun getAll(): List<ProductEntity> = emptyList()
}

private class NoOpIngredientDao : IngredientDao {
    override suspend fun insertAll(ingredients: List<IngredientEntity>) = Unit
    override suspend fun getByRxcui(rxcui: String): IngredientEntity? = null
    override suspend fun getByRxcuiList(rxcuis: List<String>): List<IngredientEntity> = emptyList()
    override suspend fun searchByName(query: String): List<IngredientEntity> = emptyList()
    override suspend fun count(): Int = 0
    override suspend fun getAll(): List<IngredientEntity> = emptyList()
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
}

private class NoOpSchedulePhaseDao : SchedulePhaseDao {
    override suspend fun insertAll(phases: List<SchedulePhaseEntity>) = Unit
    override suspend fun getPhasesForSchedule(scheduleId: String): List<SchedulePhaseEntity> = emptyList()
    override suspend fun getAll(): List<SchedulePhaseEntity> = emptyList()
}

private class NoOpDoseInstanceDao : DoseInstanceDao {
    private val empty = MutableStateFlow<List<DoseInstanceEntity>>(emptyList())
    override suspend fun insert(dose: DoseInstanceEntity) = Unit
    override suspend fun markDue(id: String) = Unit
    override suspend fun markTaken(id: String, takenEpochMs: Long) = Unit
    override suspend fun markSnoozed(id: String, snoozeUntilEpochMs: Long) = Unit
    override suspend fun markSkipped(id: String, skippedEpochMs: Long) = Unit
    override suspend fun markDueFromSnooze(id: String) = Unit
    override suspend fun markMissed(id: String, missedEpochMs: Long) = Unit
    override suspend fun getById(id: String): DoseInstanceEntity? = null
    override fun observeByMedication(medicationId: String): Flow<List<DoseInstanceEntity>> = empty.asStateFlow()
    override fun observePending(): Flow<List<DoseInstanceEntity>> = empty.asStateFlow()
    override suspend fun getNextScheduled(medicationId: String): DoseInstanceEntity? = null
    override suspend fun getNextScheduledAfter(medicationId: String, afterEpochMs: Long): DoseInstanceEntity? = null
    override fun observeActionable(beforeEpochMs: Long): Flow<List<DoseInstanceEntity>> = empty.asStateFlow()
    override suspend fun countTakenSince(medicationId: String, sinceEpochMs: Long): Int = 0
    override suspend fun getEarliestScheduled(): DoseInstanceEntity? = null
    override suspend fun countById(id: String): Int = 0
    override suspend fun getDueTimesForSchedule(scheduleId: String): List<Long> = emptyList()
    override suspend fun getLastTakenForSchedule(scheduleId: String): DoseInstanceEntity? = null
    override suspend fun getAll(): List<DoseInstanceEntity> = emptyList()
    override fun observeAllTakenSince(sinceEpochMs: Long): Flow<List<DoseInstanceEntity>> = empty.asStateFlow()
}
