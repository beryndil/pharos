package com.beryndil.pharos.medication

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.contacts.ContactRepository
import com.beryndil.pharos.data.drugref.DrugRefDatabase
import com.beryndil.pharos.data.medication.MedicationRepository
import com.beryndil.pharos.data.regimen.RegimenDatabase
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.PrescriberEntity
import com.beryndil.pharos.data.schedule.ScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Tests for the V1.3-F1 saved-contacts integration in [AddEditMedicationViewModel]:
 *  - Autocomplete fill-name-and-phone on suggestion pick.
 *  - Auto-remember prescriber/pharmacy in the [ContactRepository] on save.
 *  - Deleting a saved contact leaves medications intact (DAO-level tested in ContactRepositoryTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddEditMedicationViewModelContactsTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var regimenDb: RegimenDatabase
    private lateinit var drugRefDb: DrugRefDatabase
    private lateinit var repo: MedicationRepository
    private lateinit var scheduleRepo: ScheduleRepository
    private lateinit var contactRepo: ContactRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val exec = java.util.concurrent.Executor { it.run() }
        regimenDb = Room.inMemoryDatabaseBuilder(ctx, RegimenDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(exec).setTransactionExecutor(exec).build()
        drugRefDb = Room.inMemoryDatabaseBuilder(ctx, DrugRefDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(exec).setTransactionExecutor(exec).build()
        repo = MedicationRepository(
            medicationDao = regimenDb.medicationDao(),
            drugSearchDao = drugRefDb.drugSearchDao(),
            ingredientMapDao = drugRefDb.ingredientMapDao(),
        )
        scheduleRepo = ScheduleRepository(
            scheduleDao = regimenDb.scheduleDao(),
            schedulePhaseDao = regimenDb.schedulePhaseDao(),
            doseInstanceDao = regimenDb.doseInstanceDao(),
        )
        contactRepo = ContactRepository(
            prescriberDao = regimenDb.prescriberDao(),
            pharmacyDao = regimenDb.pharmacyDao(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        regimenDb.close()
        drugRefDb.close()
    }

    // ── Suggestion pick ───────────────────────────────────────────────────

    @Test
    fun prescriberSuggestionPicked_fillsNameAndPhone() = runTest(testDispatcher) {
        val vm = buildVm()
        val suggestion = PrescriberEntity(id = "p1", name = "Dr. House", phone = "555-1234", createdAtEpochMs = 0L)
        vm.onEvent(AddEditMedEvent.PrescriberSuggestionPicked(suggestion))
        advanceUntilIdle()
        assertEquals("Dr. House", vm.uiState.value.prescriber)
        assertEquals("555-1234", vm.uiState.value.prescriberPhone)
    }

    @Test
    fun pharmacySuggestionPicked_fillsNameAndPhone() = runTest(testDispatcher) {
        val vm = buildVm()
        val suggestion = com.beryndil.pharos.data.regimen.entity.PharmacyEntity(
            id = "ph1", name = "City Pharmacy", phone = "555-9999", createdAtEpochMs = 0L,
        )
        vm.onEvent(AddEditMedEvent.PharmacySuggestionPicked(suggestion))
        advanceUntilIdle()
        assertEquals("City Pharmacy", vm.uiState.value.pharmacy)
        assertEquals("555-9999", vm.uiState.value.pharmacyPhone)
    }

    @Test
    fun prescriberSuggestionPicked_keepsExistingPhoneWhenSuggestionPhoneIsNull() = runTest(testDispatcher) {
        val vm = buildVm()
        vm.onEvent(AddEditMedEvent.PrescriberPhoneChanged("555-0000"))
        val suggestion = PrescriberEntity(id = "p2", name = "Dr. Chase", phone = null, createdAtEpochMs = 0L)
        vm.onEvent(AddEditMedEvent.PrescriberSuggestionPicked(suggestion))
        advanceUntilIdle()
        assertEquals("Dr. Chase", vm.uiState.value.prescriber)
        // Phone should remain unchanged when suggestion has none.
        assertEquals("555-0000", vm.uiState.value.prescriberPhone)
    }

    // ── Auto-remember on save ─────────────────────────────────────────────

    @Test
    fun save_withPrescriber_autoRemembersInContactStore() = runTest(testDispatcher) {
        val vm = buildFilledVm()
        vm.onEvent(AddEditMedEvent.PrescriberChanged("Dr. Wilson"))
        vm.onEvent(AddEditMedEvent.PrescriberPhoneChanged("555-2222"))
        vm.onEvent(AddEditMedEvent.SaveRequested)
        advanceUntilIdle()
        assertTrue("Must save successfully", vm.uiState.value.savedSuccessfully)
        val prescribers = contactRepo.observePrescribers().first()
        assertEquals(1, prescribers.size)
        assertEquals("Dr. Wilson", prescribers[0].name)
        assertEquals("555-2222", prescribers[0].phone)
    }

    @Test
    fun save_withPharmacy_autoRemembersInContactStore() = runTest(testDispatcher) {
        val vm = buildFilledVm()
        vm.onEvent(AddEditMedEvent.PharmacyChanged("Green Pharmacy"))
        vm.onEvent(AddEditMedEvent.PharmacyPhoneChanged("555-8888"))
        vm.onEvent(AddEditMedEvent.SaveRequested)
        advanceUntilIdle()
        assertTrue("Must save successfully", vm.uiState.value.savedSuccessfully)
        val pharmacies = runBlocking { contactRepo.observePharmacies().first() }
        assertEquals(1, pharmacies.size)
        assertEquals("Green Pharmacy", pharmacies[0].name)
        assertEquals("555-8888", pharmacies[0].phone)
    }

    @Test
    fun save_withNoPrescriberOrPharmacy_doesNotInsertContacts() = runTest(testDispatcher) {
        val vm = buildFilledVm()
        vm.onEvent(AddEditMedEvent.SaveRequested)
        advanceUntilIdle()
        assertTrue("Must save successfully", vm.uiState.value.savedSuccessfully)
        val prescribers = contactRepo.observePrescribers().first()
        val pharmacies = contactRepo.observePharmacies().first()
        assertTrue("No prescribers should be stored when field is empty", prescribers.isEmpty())
        assertTrue("No pharmacies should be stored when field is empty", pharmacies.isEmpty())
    }

    // ── Init-order regression ─────────────────────────────────────────────

    /**
     * Regression: [AddEditMedicationViewModel] construction with a [ContactRepository] that
     * emits a non-empty prescriber list must NOT throw [NullPointerException].
     *
     * Pre-fix: `_allPrescribers`, `_allPharmacies`, and `_allActiveMeds` were declared AFTER
     * the `init {}` block. With [UnconfinedTestDispatcher] (which replicates the eager
     * [Dispatchers.Main.immediate] semantics used on Android), the `collect` lambda in
     * `startSuggestionCollection()` ran before those properties were initialised, causing
     * `MutableStateFlow.setValue()` to NPE on a null reference.
     *
     * Fix: moved all three backing-flow declarations to BEFORE `init {}`.
     */
    @Test
    fun construction_withNonEmptyContactRepository_unconfinedDispatcher_doesNotNpe() {
        val unconfinedDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(unconfinedDispatcher)
        try {
            // Pre-seed the contact store so observePrescribers() emits a non-empty list.
            runBlocking {
                contactRepo.rememberPrescriber("Dr. House", "555-1234")
                contactRepo.rememberPharmacy("City Pharmacy", "555-9999")
            }
            // Must not throw NullPointerException during construction.
            val vm = AddEditMedicationViewModel(
                repository = repo,
                scheduleRepository = scheduleRepo,
                contactRepository = contactRepo,
                savedStateHandle = SavedStateHandle(),
                ioDispatcher = unconfinedDispatcher,
            )
            // Suggestions are empty until a query is typed (blank query → empty filter result).
            assertTrue(
                "Prescriber suggestions must be empty before any query",
                vm.uiState.value.prescriberSuggestions.isEmpty(),
            )
            assertTrue(
                "Pharmacy suggestions must be empty before any query",
                vm.uiState.value.pharmacySuggestions.isEmpty(),
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun buildVm(): AddEditMedicationViewModel =
        AddEditMedicationViewModel(
            repository = repo,
            scheduleRepository = scheduleRepo,
            contactRepository = contactRepo,
            savedStateHandle = SavedStateHandle(),
            ioDispatcher = testDispatcher,
        )

    private fun buildFilledVm(): AddEditMedicationViewModel {
        val vm = buildVm()
        vm.onEvent(AddEditMedEvent.NameQueryChanged("TestMed"))
        vm.onEvent(AddEditMedEvent.ContinueAsCustom)
        vm.onEvent(AddEditMedEvent.StrengthChanged("10 mg"))
        vm.onEvent(AddEditMedEvent.FormSelected(MedicationForm.TABLET))
        vm.onEvent(AddEditMedEvent.StartDateSelected(LocalDate.of(2026, 1, 1)))
        vm.onEvent(AddEditMedEvent.DoseAmountChanged("1 tablet"))
        return vm
    }
}
