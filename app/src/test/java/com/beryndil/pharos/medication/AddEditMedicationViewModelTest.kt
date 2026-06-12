package com.beryndil.pharos.medication

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.data.drugref.DrugRefDatabase
import com.beryndil.pharos.data.drugref.entity.IngredientEntity
import com.beryndil.pharos.data.drugref.entity.ProductEntity
import com.beryndil.pharos.data.medication.MedicationRepository
import com.beryndil.pharos.data.regimen.RegimenDatabase
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.data.schedule.ScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.UUID

/**
 * Unit tests for [AddEditMedicationViewModel] (Slice 2).
 *
 * Uses [StandardTestDispatcher] for both Main and [ioDispatcher] so all dispatching goes
 * through a single [kotlinx.coroutines.test.TestCoroutineScheduler]. [advanceUntilIdle]
 * drains all pending coroutines — including nested [androidx.lifecycle.ViewModel.viewModelScope]
 * launches — before assertions. [runBlocking] seeds the DB synchronously to avoid scheduler
 * interactions in setUp.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddEditMedicationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var regimenDb: RegimenDatabase
    private lateinit var drugRefDb: DrugRefDatabase
    private lateinit var repo: MedicationRepository
    private lateinit var scheduleRepo: ScheduleRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // Direct executor: Room queries run inline (no real background threads).
        // This ensures withContext(testDispatcher) { dao.insert(...) } stays on
        // testDispatcher — advanceUntilIdle() can drain the entire call chain.
        val directExec = java.util.concurrent.Executor { it.run() }
        regimenDb = Room.inMemoryDatabaseBuilder(ctx, RegimenDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExec)
            .setTransactionExecutor(directExec)
            .build()
        drugRefDb = Room.inMemoryDatabaseBuilder(ctx, DrugRefDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExec)
            .setTransactionExecutor(directExec)
            .build()
        repo = MedicationRepository(
            medicationDao = regimenDb.medicationDao(),
            productDao = drugRefDb.productDao(),
            ingredientDao = drugRefDb.ingredientDao(),
        )
        scheduleRepo = ScheduleRepository(
            scheduleDao = regimenDb.scheduleDao(),
            schedulePhaseDao = regimenDb.schedulePhaseDao(),
            doseInstanceDao = regimenDb.doseInstanceDao(),
        )
        // runBlocking: seed DB synchronously — no scheduler interaction needed.
        runBlocking { seedDrugRefFixture() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        regimenDb.close()
        drugRefDb.close()
    }

    // ── Initial state ─────────────────────────────────────────────────────

    @Test
    fun initialState_isSearchStep() {
        val vm = buildViewModel()
        assertEquals(FormStep.SEARCH, vm.uiState.value.step)
        assertNull(vm.uiState.value.editMedId)
    }

    @Test
    fun editMode_loadsExistingMed_andStartsAtDetailsStep() = runTest(testDispatcher) {
        val med = sampleMedication()
        regimenDb.medicationDao().insert(med)

        val vm = buildViewModel(editMedId = med.id)
        advanceUntilIdle() // Let loadExistingMedication coroutine complete.

        val state = vm.uiState.value
        assertEquals(FormStep.DETAILS, state.step)
        assertEquals(med.name, state.displayName)
        assertEquals(med.strength, state.strength)
        assertFalse(state.isFreeText)
    }

    // ── Required-field validation ─────────────────────────────────────────

    @Test
    fun saveRequested_missingStrength_setsStrengthError() = runTest(testDispatcher) {
        val vm = buildFilledViewModel(strength = "")
        // Validation is synchronous; no advanceUntilIdle needed.
        vm.onEvent(AddEditMedEvent.SaveRequested)
        assertTrue(vm.uiState.value.strengthError)
        assertFalse(vm.uiState.value.savedSuccessfully)
    }

    @Test
    fun saveRequested_missingForm_setsFormError() = runTest(testDispatcher) {
        val vm = buildFilledViewModel(form = null)
        vm.onEvent(AddEditMedEvent.SaveRequested)
        assertTrue(vm.uiState.value.formError)
        assertFalse(vm.uiState.value.savedSuccessfully)
    }

    @Test
    fun saveRequested_missingStartDate_setsStartDateError() = runTest(testDispatcher) {
        val vm = buildFilledViewModel(startDate = null)
        vm.onEvent(AddEditMedEvent.SaveRequested)
        assertTrue(vm.uiState.value.startDateError)
        assertFalse(vm.uiState.value.savedSuccessfully)
    }

    @Test
    fun saveRequested_allFieldsValid_savesSuccessfully() = runTest(testDispatcher) {
        val vm = buildFilledViewModel()
        vm.onEvent(AddEditMedEvent.SaveRequested)
        advanceUntilIdle() // Run duplicate-check + save coroutines.
        assertTrue("savedSuccessfully must be true after valid save", vm.uiState.value.savedSuccessfully)
    }

    // ── Duplicate warning ─────────────────────────────────────────────────

    @Test
    fun saveRequested_duplicateIngredient_showsWarningBeforeSave() = runTest(testDispatcher) {
        val existing = sampleMedication(ingredientsJson = """["161"]""")
        regimenDb.medicationDao().insert(existing)

        val vm = buildFilledViewModel(ingredientRxcuis = listOf("161"))
        vm.onEvent(AddEditMedEvent.SaveRequested)
        advanceUntilIdle() // Run duplicate-check coroutine.

        assertTrue("Duplicate warning dialog must be shown", vm.uiState.value.showDuplicateWarning)
        assertFalse("Must NOT save yet", vm.uiState.value.savedSuccessfully)
    }

    @Test
    fun duplicateWarningDismissed_doesNotSave() = runTest(testDispatcher) {
        val existing = sampleMedication(ingredientsJson = """["161"]""")
        regimenDb.medicationDao().insert(existing)

        val vm = buildFilledViewModel(ingredientRxcuis = listOf("161"))
        vm.onEvent(AddEditMedEvent.SaveRequested)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showDuplicateWarning)

        vm.onEvent(AddEditMedEvent.DuplicateWarningDismissed)
        assertFalse(vm.uiState.value.showDuplicateWarning)
        assertFalse("Must NOT save after dismissal", vm.uiState.value.savedSuccessfully)
    }

    @Test
    fun duplicateWarningConfirmed_savesSuccessfully() = runTest(testDispatcher) {
        val existing = sampleMedication(ingredientsJson = """["161"]""")
        regimenDb.medicationDao().insert(existing)

        val vm = buildFilledViewModel(ingredientRxcuis = listOf("161"))
        vm.onEvent(AddEditMedEvent.SaveRequested)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showDuplicateWarning)

        vm.onEvent(AddEditMedEvent.DuplicateWarningConfirmed)
        advanceUntilIdle() // Run save coroutine.
        assertTrue("Must save successfully after confirmation", vm.uiState.value.savedSuccessfully)
    }

    // ── Step navigation ───────────────────────────────────────────────────

    @Test
    fun drugSelected_movesToConfirmStep() {
        val vm = buildViewModel()
        vm.onEvent(AddEditMedEvent.DrugSelected(tylenolDrug()))
        assertEquals(FormStep.CONFIRM, vm.uiState.value.step)
    }

    @Test
    fun confirmDrug_movesToDetailsStep_andPreFillsFields() {
        val vm = buildViewModel()
        vm.onEvent(AddEditMedEvent.DrugSelected(tylenolDrug()))
        vm.onEvent(AddEditMedEvent.ConfirmDrug)

        val state = vm.uiState.value
        assertEquals(FormStep.DETAILS, state.step)
        assertEquals("Tylenol 500 MG", state.displayName)
        assertEquals("500 mg", state.strength)
        assertEquals(MedicationForm.TABLET, state.selectedForm)
        assertEquals(listOf("161"), state.ingredientRxcuis)
        assertFalse(state.isFreeText)
    }

    @Test
    fun continueAsCustom_movesToDetailsStep_withFreeTextTrue() {
        val vm = buildViewModel()
        vm.onEvent(AddEditMedEvent.NameQueryChanged("UnknownDrug"))
        vm.onEvent(AddEditMedEvent.ContinueAsCustom)

        val state = vm.uiState.value
        assertEquals(FormStep.DETAILS, state.step)
        assertTrue(state.isFreeText)
        assertTrue(state.ingredientRxcuis.isEmpty())
    }

    @Test
    fun stepBack_fromConfirm_returnsToSearch() {
        val vm = buildViewModel()
        vm.onEvent(AddEditMedEvent.DrugSelected(tylenolDrug()))
        vm.onEvent(AddEditMedEvent.StepBack)
        assertEquals(FormStep.SEARCH, vm.uiState.value.step)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun buildViewModel(editMedId: String? = null): AddEditMedicationViewModel {
        val ssh = if (editMedId != null) {
            SavedStateHandle(mapOf("medId" to editMedId))
        } else {
            SavedStateHandle()
        }
        // Inject testDispatcher as ioDispatcher so withContext(ioDispatcher) runs on the same
        // TestCoroutineScheduler as viewModelScope — advanceUntilIdle() drains both.
        return AddEditMedicationViewModel(
            repository = repo,
            scheduleRepository = scheduleRepo,
            savedStateHandle = ssh,
            ioDispatcher = testDispatcher,
        )
    }

    /** Pre-loads the VM at DETAILS step with valid required fields. */
    private fun buildFilledViewModel(
        strength: String = "500 mg",
        form: MedicationForm? = MedicationForm.TABLET,
        startDate: LocalDate? = LocalDate.of(2026, 1, 1),
        ingredientRxcuis: List<String> = emptyList(),
    ): AddEditMedicationViewModel {
        val vm = buildViewModel()
        if (ingredientRxcuis.isEmpty()) {
            vm.onEvent(AddEditMedEvent.NameQueryChanged("TestDrug"))
            vm.onEvent(AddEditMedEvent.ContinueAsCustom)
        } else {
            val drug = com.beryndil.pharos.medication.model.DrugSearchResult(
                rxcui = "209387",
                name = "TestDrug",
                strength = "500 mg",
                rxNormForm = "Oral Tablet",
                ingredientRxcuis = ingredientRxcuis,
                ingredientNames = ingredientRxcuis,
            )
            vm.onEvent(AddEditMedEvent.DrugSelected(drug))
            vm.onEvent(AddEditMedEvent.ConfirmDrug)
        }
        if (strength.isNotBlank()) vm.onEvent(AddEditMedEvent.StrengthChanged(strength))
        if (form != null) vm.onEvent(AddEditMedEvent.FormSelected(form))
        if (startDate != null) vm.onEvent(AddEditMedEvent.StartDateSelected(startDate))
        vm.onEvent(AddEditMedEvent.DoseAmountChanged("1 tablet"))
        return vm
    }

    private fun tylenolDrug() = com.beryndil.pharos.medication.model.DrugSearchResult(
        rxcui = "209387",
        name = "Tylenol 500 MG",
        strength = "500 mg",
        rxNormForm = "Oral Tablet",
        ingredientRxcuis = listOf("161"),
        ingredientNames = listOf("Acetaminophen"),
    )

    private suspend fun seedDrugRefFixture() {
        drugRefDb.ingredientDao().insertAll(
            listOf(
                IngredientEntity(rxcui = "161", name = "Acetaminophen", tty = "IN"),
                IngredientEntity(rxcui = "41493", name = "Metoprolol Succinate", tty = "IN"),
            ),
        )
        drugRefDb.productDao().insertAll(
            listOf(
                ProductEntity(
                    rxcui = "209387",
                    name = "Tylenol 500 MG Oral Tablet",
                    ingredientsJson = """["161"]""",
                    form = "Oral Tablet",
                    strength = "500 mg",
                ),
            ),
        )
    }

    private fun sampleMedication(
        id: String = UUID.randomUUID().toString(),
        name: String = "Existing Medication",
        ingredientsJson: String = """["161"]""",
    ) = MedicationEntity(
        id = id,
        name = name,
        rxcui = null,
        ingredientsJson = ingredientsJson,
        strength = "100 mg",
        form = MedicationForm.TABLET.name,
        doseAmount = "1 tablet",
        prescriber = null,
        pharmacy = null,
        purpose = null,
        isFreeText = false,
        status = MedicationStatus.ACTIVE.name,
        startEpochMs = 1_700_000_000_000L,
        endEpochMs = null,
        createdAtEpochMs = 1_700_000_000_000L,
        updatedAtEpochMs = 1_700_000_000_000L,
    )
}
