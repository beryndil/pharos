package com.beryndil.pharos.medication

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.contacts.ContactRepository
import com.beryndil.pharos.data.drugref.DrugLabelRepository
import com.beryndil.pharos.medication.BarcodeNdcService
import com.beryndil.pharos.data.medication.MedicationRepository
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.data.regimen.entity.PharmacyEntity
import com.beryndil.pharos.data.regimen.entity.PrescriberEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleType
import com.beryndil.pharos.data.schedule.ScheduleRepository
import com.beryndil.pharos.medication.model.DrugSearchResult
import com.beryndil.pharos.medication.model.DuplicateWarning
import com.beryndil.pharos.schedule.model.ScheduleInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Steps within the Add/Edit medication flow (single-screen wizard). */
enum class FormStep { SEARCH, CONFIRM, DETAILS }

/**
 * Reason a save attempt failed — maps to a string resource in the composable (i18n-safe,
 * Standards §7 — no hardcoded user-facing strings in the ViewModel).
 */
enum class SaveError { GENERAL }

@Immutable
data class AddEditMedicationUiState(
    /** Null when adding a new medication. Non-null when editing. */
    val editMedId: String? = null,

    val step: FormStep = FormStep.SEARCH,

    // ── SEARCH step ───────────────────────────────────────────────────────
    val nameQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<DrugSearchResult> = emptyList(),

    // ── CONFIRM step ──────────────────────────────────────────────────────
    /** Drug picked from search results, pending user confirmation. */
    val pendingDrug: DrugSearchResult? = null,

    // ── DETAILS step ─────────────────────────────────────────────────────
    /** True for free-text fallback meds (spec §2.11). */
    val isFreeText: Boolean = false,

    /** Display name as confirmed or typed by the user. */
    val displayName: String = "",

    /** Ingredient names for confirmation-screen display only. */
    val ingredientNames: List<String> = emptyList(),

    /**
     * RxCUI strings of active ingredients — written to [MedicationEntity.ingredientsJson] and
     * used for duplicate-ingredient detection. Empty for free-text meds.
     */
    val ingredientRxcuis: List<String> = emptyList(),

    val strength: String = "",
    val selectedForm: MedicationForm? = null,
    val doseAmount: String = "",
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val prescriber: String = "",
    val prescriberPhone: String = "",
    val prescriberPractice: String = "",
    val pharmacy: String = "",
    val pharmacyPhone: String = "",
    val purpose: String = "",
    val notes: String = "",

    // ── Barcode scanner ───────────────────────────────────────────────────
    val showBarcodeScanner: Boolean = false,
    /** True while a background NDC lookup is running after a barcode scan. */
    val isBarcodeScanning: Boolean = false,

    /** Autocomplete suggestions for the prescriber name field, drawn from the saved store. */
    val prescriberSuggestions: List<PrescriberEntity> = emptyList(),

    /** Autocomplete suggestions for the pharmacy name field, drawn from the saved store. */
    val pharmacySuggestions: List<PharmacyEntity> = emptyList(),

    /** Preserved from the existing entity so edit mode doesn't clobber createdAt. */
    val originalCreatedAtMs: Long? = null,

    // ── Schedule ──────────────────────────────────────────────────────────
    val scheduleInput: ScheduleInput = ScheduleInput(),
    val scheduleValidationError: Boolean = false,

    // ── Critical reminders (A1 — Critical Alerts) ─────────────────────────
    /**
     * True when the user has designated this medication as critical (spec §3.1).
     * Default false — non-critical is the safe default.
     */
    val isCritical: Boolean = false,

    // ── Miss window (spec §2.6, G1) ───────────────────────────────────────
    /**
     * User-entered text for the miss-window field in minutes. Stored as a String so the
     * text field can hold an intermediate (partially typed) value without forcing a number.
     * Must convert to Int in [5, 360] at save time; defaults to "60".
     */
    val missWindowMinutesText: String = "60",
    /** True when [missWindowMinutesText] is outside the valid range [5, 360]. */
    val missWindowMinutesError: Boolean = false,
    /**
     * True when the user has just toggled isCritical=true for the FIRST time (no other critical
     * active medication exists) AND DND policy access is not yet granted. The UI reacts by showing
     * a rationale dialog and routing to Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS.
     */
    val showDndPermissionRationale: Boolean = false,

    // ── Substitution link (V1.3-F2) ───────────────────────────────────────
    /**
     * ID of the medication this one substitutes for. Null = no link. Reference framing only
     * (Law 3 — never "take B instead of A"; this records that one was switched from another).
     */
    val substituteForMedId: String? = null,
    /** Resolved display name of the linked medication (for the picker label). */
    val substituteForMedName: String? = null,
    /** Optional free-text note attached to the substitution link. */
    val substituteNote: String = "",
    /** Active medications available as substitution link targets (excludes self on edit). */
    val substituteMedOptions: List<MedicationEntity> = emptyList(),

    // ── Validation ────────────────────────────────────────────────────────
    val strengthError: Boolean = false,
    val formError: Boolean = false,
    val startDateError: Boolean = false,

    // ── Duplicate warning (spec §2.4) ─────────────────────────────────────
    val pendingDuplicateWarnings: List<DuplicateWarning> = emptyList(),
    val showDuplicateWarning: Boolean = false,

    // ── Save result ───────────────────────────────────────────────────────
    val isSaving: Boolean = false,
    val savedSuccessfully: Boolean = false,
    val saveError: SaveError? = null,
)

/** User-driven events dispatched from the UI to the ViewModel. */
sealed interface AddEditMedEvent {
    data class NameQueryChanged(val query: String) : AddEditMedEvent
    data class DrugSelected(val drug: DrugSearchResult) : AddEditMedEvent
    data object ContinueAsCustom : AddEditMedEvent
    data object ConfirmDrug : AddEditMedEvent
    data object StepBack : AddEditMedEvent
    data class StrengthChanged(val value: String) : AddEditMedEvent
    data class FormSelected(val form: MedicationForm) : AddEditMedEvent
    data class DoseAmountChanged(val value: String) : AddEditMedEvent
    data class StartDateSelected(val date: LocalDate) : AddEditMedEvent
    data class EndDateSelected(val date: LocalDate?) : AddEditMedEvent
    data class PrescriberChanged(val value: String) : AddEditMedEvent
    data class PrescriberPhoneChanged(val value: String) : AddEditMedEvent
    data class PrescriberPracticeChanged(val value: String) : AddEditMedEvent
    /** User picked an autocomplete suggestion — fills name, phone, and practice. */
    data class PrescriberSuggestionPicked(val prescriber: PrescriberEntity) : AddEditMedEvent
    data class PharmacyChanged(val value: String) : AddEditMedEvent
    data class PharmacyPhoneChanged(val value: String) : AddEditMedEvent
    /** User picked an autocomplete suggestion — fills both name and phone. */
    data class PharmacySuggestionPicked(val pharmacy: PharmacyEntity) : AddEditMedEvent
    data class PurposeChanged(val value: String) : AddEditMedEvent
    data class NotesChanged(val value: String) : AddEditMedEvent
    data object BarcodeScanRequested : AddEditMedEvent
    data object BarcodeScanDismissed : AddEditMedEvent
    /** Raw barcode value from the scanner — triggers an openFDA NDC lookup to pre-fill fields. */
    data class BarcodeDetected(val rawValue: String) : AddEditMedEvent
    data class ScheduleInputChanged(val input: ScheduleInput) : AddEditMedEvent
    data class IsCriticalToggled(val value: Boolean) : AddEditMedEvent
    data class MissWindowMinutesChanged(val value: String) : AddEditMedEvent
    data object DndPermissionRationaleDismissed : AddEditMedEvent
    /** User selected (or cleared) the substitute-for medication. Null clears the link. */
    data class SubstituteForChanged(val medId: String?) : AddEditMedEvent
    /** User typed in the optional substitution note field. */
    data class SubstituteNoteChanged(val value: String) : AddEditMedEvent
    data object SaveRequested : AddEditMedEvent
    data object DuplicateWarningConfirmed : AddEditMedEvent
    data object DuplicateWarningDismissed : AddEditMedEvent
    data object ErrorDismissed : AddEditMedEvent
}

@OptIn(FlowPreview::class)
class AddEditMedicationViewModel(
    private val repository: MedicationRepository,
    private val scheduleRepository: ScheduleRepository,
    private val contactRepository: ContactRepository? = null,
    savedStateHandle: SavedStateHandle,
    /**
     * IO dispatcher injected for testability — production code always passes [Dispatchers.IO];
     * tests pass an [UnconfinedTestDispatcher] so all work runs synchronously.
     */
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    /**
     * Optional drug label repository. When provided, a background fetch is triggered after a
     * successful save so the reference screen has data ready (spec §2.10 "fetched on add").
     * Null in existing tests that don't provide it — the label fetch is simply skipped.
     */
    private val drugLabelRepository: DrugLabelRepository? = null,
    /**
     * Lambda that returns true when ACCESS_NOTIFICATION_POLICY has been granted. Injected for
     * testability (tests pass { true } / { false }; production supplies NotificationManager check).
     */
    private val isDndAccessGranted: () -> Boolean = { true },
    /**
     * Suspend lambda that returns the current list of active critical medications. Injected for
     * testability (tests pass a lambda returning a controlled list; production delegates to
     * [MedicationRepository.getCriticalActiveMedications]). This avoids needing Room in unit tests.
     */
    private val fetchCriticalMeds: suspend () -> List<com.beryndil.pharos.data.regimen.entity.MedicationEntity> =
        repository::getCriticalActiveMedications,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddEditMedicationUiState())
    val uiState: StateFlow<AddEditMedicationUiState> = _uiState.asStateFlow()

    private val _searchTrigger = MutableSharedFlow<String>(replay = 1)

    // ── Internal contact caches for autocomplete ─────────────────────────
    // Declared BEFORE init {} so that startSuggestionCollection() and
    // startSubstituteOptionsCollection() (called from init) can safely write to these flows.
    // Kotlin initialises properties in declaration order; placing these after init {} left the
    // backing fields null when the launched-coroutine lambdas ran (NPE on _allPrescribers.value = all).
    private val _allPrescribers = MutableStateFlow<List<PrescriberEntity>>(emptyList())
    private val _allPharmacies = MutableStateFlow<List<PharmacyEntity>>(emptyList())

    /**
     * Cache of all active medications for the substitute-for picker. Kept in sync with
     * [MedicationRepository.observeActiveMedications] so the picker reflects live DB state.
     * Also declared before init {} for the same init-order reason as [_allPrescribers].
     */
    private val _allActiveMeds = MutableStateFlow<List<MedicationEntity>>(emptyList())

    init {
        val editMedId: String? = savedStateHandle["medId"]
        if (editMedId != null) {
            _uiState.update { it.copy(editMedId = editMedId) }
            loadExistingMedication(editMedId)
        }
        startSearchDebounce()
        startSuggestionCollection()
        startSubstituteOptionsCollection()
    }

    fun onEvent(event: AddEditMedEvent) {
        when (event) {
            is AddEditMedEvent.NameQueryChanged -> onNameQueryChanged(event.query)
            is AddEditMedEvent.DrugSelected -> onDrugSelected(event.drug)
            is AddEditMedEvent.ContinueAsCustom -> onContinueAsCustom()
            is AddEditMedEvent.ConfirmDrug -> onConfirmDrug()
            is AddEditMedEvent.StepBack -> onStepBack()
            is AddEditMedEvent.StrengthChanged ->
                _uiState.update { it.copy(strength = event.value, strengthError = false) }
            is AddEditMedEvent.FormSelected ->
                _uiState.update { state ->
                    state.copy(
                        selectedForm = event.form,
                        formError = false,
                        doseAmount = autoFormatDoseAmount(state.doseAmount, event.form),
                    )
                }
            is AddEditMedEvent.DoseAmountChanged ->
                _uiState.update { state ->
                    state.copy(doseAmount = autoFormatDoseAmount(event.value, state.selectedForm))
                }
            is AddEditMedEvent.StartDateSelected ->
                _uiState.update { it.copy(startDate = event.date, startDateError = false) }
            is AddEditMedEvent.EndDateSelected ->
                _uiState.update { it.copy(endDate = event.date) }
            is AddEditMedEvent.PrescriberChanged ->
                _uiState.update { state ->
                    state.copy(
                        prescriber = event.value,
                        prescriberSuggestions = filterContacts(_allPrescribers.value, event.value),
                    )
                }
            is AddEditMedEvent.PrescriberPhoneChanged ->
                _uiState.update { it.copy(prescriberPhone = event.value) }
            is AddEditMedEvent.PrescriberPracticeChanged ->
                _uiState.update { it.copy(prescriberPractice = event.value) }
            is AddEditMedEvent.PrescriberSuggestionPicked ->
                _uiState.update {
                    it.copy(
                        prescriber = event.prescriber.name,
                        prescriberPhone = event.prescriber.phone ?: it.prescriberPhone,
                        prescriberPractice = event.prescriber.practice ?: it.prescriberPractice,
                    )
                }
            is AddEditMedEvent.PharmacyChanged ->
                _uiState.update { state ->
                    state.copy(
                        pharmacy = event.value,
                        pharmacySuggestions = filterContacts(_allPharmacies.value, event.value),
                    )
                }
            is AddEditMedEvent.PharmacyPhoneChanged ->
                _uiState.update { it.copy(pharmacyPhone = event.value) }
            is AddEditMedEvent.PharmacySuggestionPicked ->
                _uiState.update {
                    it.copy(
                        pharmacy = event.pharmacy.name,
                        pharmacyPhone = event.pharmacy.phone ?: it.pharmacyPhone,
                    )
                }
            is AddEditMedEvent.PurposeChanged ->
                _uiState.update { it.copy(purpose = event.value) }
            is AddEditMedEvent.NotesChanged ->
                _uiState.update { it.copy(notes = event.value) }
            is AddEditMedEvent.BarcodeScanRequested ->
                _uiState.update { it.copy(showBarcodeScanner = true) }
            is AddEditMedEvent.BarcodeScanDismissed ->
                _uiState.update { it.copy(showBarcodeScanner = false) }
            is AddEditMedEvent.BarcodeDetected -> onBarcodeDetected(event.rawValue)
            is AddEditMedEvent.ScheduleInputChanged ->
                _uiState.update { it.copy(scheduleInput = event.input, scheduleValidationError = false) }
            is AddEditMedEvent.IsCriticalToggled -> onIsCriticalToggled(event.value)
            is AddEditMedEvent.MissWindowMinutesChanged ->
                _uiState.update {
                    val valid = event.value.toIntOrNull()?.let { it in 5..360 } ?: false
                    it.copy(
                        missWindowMinutesText = event.value,
                        missWindowMinutesError = event.value.isNotEmpty() && !valid,
                    )
                }
            is AddEditMedEvent.DndPermissionRationaleDismissed ->
                _uiState.update { it.copy(showDndPermissionRationale = false) }
            is AddEditMedEvent.SubstituteForChanged -> onSubstituteForChanged(event.medId)
            is AddEditMedEvent.SubstituteNoteChanged ->
                _uiState.update { it.copy(substituteNote = event.value) }
            is AddEditMedEvent.SaveRequested -> onSaveRequested()
            is AddEditMedEvent.DuplicateWarningConfirmed -> performSave()
            is AddEditMedEvent.DuplicateWarningDismissed ->
                _uiState.update { it.copy(showDuplicateWarning = false, isSaving = false) }
            is AddEditMedEvent.ErrorDismissed ->
                _uiState.update { it.copy(saveError = null) }
        }
    }

    // ── Private ───────────────────────────────────────────────────────────

    /**
     * Handles toggling isCritical. When enabled for the FIRST critical med and DND access is not
     * yet granted, shows the rationale dialog so the user can grant it (spec §4 lazy permission).
     */
    private fun onIsCriticalToggled(value: Boolean) {
        _uiState.update { it.copy(isCritical = value) }
        if (!value) return
        // Lazy DND permission: only prompt on the first critical med and only if not yet granted.
        if (isDndAccessGranted()) return
        viewModelScope.launch {
            val existingCritical = withContext(ioDispatcher) {
                fetchCriticalMeds()
            }
            // Exclude the med being edited from the count (it may already be critical in the DB).
            val othersCount = existingCritical.count { it.id != _uiState.value.editMedId }
            if (othersCount == 0) {
                _uiState.update { it.copy(showDndPermissionRationale = true) }
            }
        }
    }

    private fun loadExistingMedication(medId: String) {
        viewModelScope.launch {
            val med = withContext(ioDispatcher) { repository.getMedication(medId) }
                ?: return@launch
            val ingredientRxcuis = repository.parseIngredientRxcuis(med.ingredientsJson)
            val ingredientNames = withContext(ioDispatcher) {
                repository.getIngredientNames(ingredientRxcuis)
            }

            // Load existing schedule
            val scheduleInput = withContext(ioDispatcher) {
                val schedule = scheduleRepository.getActiveSchedule(medId)
                if (schedule != null) {
                    val phases = scheduleRepository.getSchedulePhases(schedule.id)
                    scheduleRepository.entityToScheduleInput(schedule, phases)
                } else {
                    ScheduleInput()
                }
            }

            // Resolve substitute-for display name from cache (fast path) or DB (fallback).
            val substituteName = med.substituteForMedId?.let { subId ->
                _allActiveMeds.value.find { it.id == subId }?.name
                    ?: withContext(ioDispatcher) { repository.getMedication(subId)?.name }
            }

            _uiState.update {
                it.copy(
                    step = FormStep.DETAILS,
                    isFreeText = med.isFreeText,
                    displayName = med.name,
                    ingredientNames = ingredientNames,
                    ingredientRxcuis = ingredientRxcuis,
                    strength = med.strength,
                    selectedForm = runCatching { MedicationForm.valueOf(med.form) }.getOrNull(),
                    doseAmount = med.doseAmount,
                    startDate = Instant.ofEpochMilli(med.startEpochMs)
                        .atZone(ZoneOffset.UTC).toLocalDate(),
                    endDate = med.endEpochMs?.let { ms ->
                        Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                    },
                    prescriber = med.prescriber ?: "",
                    prescriberPhone = med.prescriberPhone ?: "",
                    prescriberPractice = med.prescriberPractice ?: "",
                    pharmacy = med.pharmacy ?: "",
                    pharmacyPhone = med.pharmacyPhone ?: "",
                    purpose = med.purpose ?: "",
                    notes = med.notes ?: "",
                    substituteForMedId = med.substituteForMedId,
                    substituteForMedName = substituteName,
                    substituteNote = med.substituteNote ?: "",
                    isCritical = med.isCritical,
                    missWindowMinutesText = med.missWindowMinutes.toString(),
                    originalCreatedAtMs = med.createdAtEpochMs,
                    scheduleInput = scheduleInput,
                )
            }
        }
    }

    /**
     * Collects prescriber/pharmacy lists from the contact store. [_allPrescribers] and
     * [_allPharmacies] are then filtered synchronously on each name-field change event.
     */
    private fun startSuggestionCollection() {
        if (contactRepository == null) return
        viewModelScope.launch {
            contactRepository.observePrescribers().collect { all ->
                _allPrescribers.value = all
                // Re-filter on DB change so stale suggestions don't linger.
                _uiState.update { state ->
                    state.copy(prescriberSuggestions = filterContacts(all, state.prescriber))
                }
            }
        }
        viewModelScope.launch {
            contactRepository.observePharmacies().collect { all ->
                _allPharmacies.value = all
                _uiState.update { state ->
                    state.copy(pharmacySuggestions = filterContacts(all, state.pharmacy))
                }
            }
        }
    }

    private fun filterContacts(all: List<PrescriberEntity>, query: String): List<PrescriberEntity> =
        if (query.isBlank()) emptyList()
        else all.filter { it.name.contains(query, ignoreCase = true) && !it.name.equals(query, ignoreCase = true) }

    @JvmName("filterPharmacyContacts")
    private fun filterContacts(all: List<PharmacyEntity>, query: String): List<PharmacyEntity> =
        if (query.isBlank()) emptyList()
        else all.filter { it.name.contains(query, ignoreCase = true) && !it.name.equals(query, ignoreCase = true) }

    /**
     * Keeps [AddEditMedicationUiState.substituteMedOptions] in sync with ALL medications
     * (V1.3-F2). Includes ENDED and PAUSED meds so the user can link a new medication to
     * one they have already stopped taking. The med being edited is excluded from its own
     * picker. Options are sorted: active first, then paused, then ended; alpha within each tier.
     */
    private fun startSubstituteOptionsCollection() {
        viewModelScope.launch {
            repository.observeAllMedications().collect { meds ->
                _allActiveMeds.value = meds
                val editId = _uiState.value.editMedId
                val options = meds
                    .filter { it.id != editId }
                    .sortedWith(
                        compareBy(
                            { statusSortKey(it.status) },
                            { it.name },
                        ),
                    )
                _uiState.update { state ->
                    state.copy(substituteMedOptions = options)
                }
            }
        }
    }

    private fun statusSortKey(status: String): Int = when (
        runCatching { MedicationStatus.valueOf(status) }.getOrDefault(MedicationStatus.ACTIVE)
    ) {
        MedicationStatus.ACTIVE -> 0
        MedicationStatus.PAUSED -> 1
        MedicationStatus.ENDED  -> 2
    }

    /**
     * Handles the user picking (or clearing) a substitute-for target.
     * Resolves the display name from the cached active-med list so the UI reflects the
     * selection immediately without a DB round-trip.
     */
    private fun onSubstituteForChanged(medId: String?) {
        val name = medId?.let { id -> _allActiveMeds.value.find { it.id == id }?.name }
        _uiState.update { it.copy(substituteForMedId = medId, substituteForMedName = name) }
    }

    private fun startSearchDebounce() {
        viewModelScope.launch {
            _searchTrigger
                .debounce(300)
                .collectLatest { query ->
                    if (query.length >= 2) {
                        val results = withContext(ioDispatcher) { repository.searchDrugs(query) }
                        _uiState.update { it.copy(searchResults = results, isSearching = false) }
                    } else {
                        _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
                    }
                }
        }
    }

    private fun onNameQueryChanged(query: String) {
        _uiState.update { it.copy(nameQuery = query, isSearching = query.length >= 2) }
        viewModelScope.launch { _searchTrigger.emit(query) }
    }

    private fun onDrugSelected(drug: DrugSearchResult) {
        _uiState.update { it.copy(pendingDrug = drug, step = FormStep.CONFIRM) }
    }

    private fun onContinueAsCustom() {
        val name = _uiState.value.nameQuery.trim()
        _uiState.update {
            it.copy(
                step = FormStep.DETAILS,
                isFreeText = true,
                displayName = name,
                ingredientNames = emptyList(),
                ingredientRxcuis = emptyList(),
                // Clear any previously selected drug
                pendingDrug = null,
            )
        }
    }

    private fun onConfirmDrug() {
        val drug = _uiState.value.pendingDrug ?: return
        // The RxNorm pipeline schema (v2) does not expose strength or form as separate columns —
        // they are encoded in the drug name for clinical types (DECISIONS.md G2b). The user
        // enters strength and form in the DETAILS step. Do not pre-fill them here.
        _uiState.update {
            it.copy(
                step = FormStep.DETAILS,
                isFreeText = false,
                displayName = drug.name,
                ingredientNames = drug.ingredientNames,
                ingredientRxcuis = drug.ingredientRxcuis,
            )
        }
    }

    private fun onStepBack() {
        when (_uiState.value.step) {
            FormStep.CONFIRM ->
                _uiState.update { it.copy(step = FormStep.SEARCH, pendingDrug = null) }
            FormStep.DETAILS -> {
                val backStep = if (_uiState.value.isFreeText) FormStep.SEARCH else FormStep.CONFIRM
                _uiState.update { it.copy(step = backStep) }
            }
            FormStep.SEARCH -> { /* Navigation back handled by the nav controller */ }
        }
    }

    /**
     * Handles a raw barcode value from the scanner.
     *
     * Launches an openFDA NDC lookup in the background. On success the form jumps directly to
     * DETAILS with name, strength, and form pre-filled (as a free-text entry — no RxNorm
     * confirmation step needed). On failure the name query is pre-populated with the raw value
     * so the normal search flow continues.
     */
    private fun onBarcodeDetected(rawValue: String) {
        _uiState.update { it.copy(showBarcodeScanner = false, isBarcodeScanning = true) }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { BarcodeNdcService.lookup(rawValue) }
            _uiState.update { state ->
                if (result != null) {
                    state.copy(
                        isBarcodeScanning = false,
                        step = FormStep.DETAILS,
                        isFreeText = true,
                        displayName = result.name,
                        strength = result.strength ?: state.strength,
                        selectedForm = result.form ?: state.selectedForm,
                        doseAmount = if (result.form != null && state.doseAmount.isBlank()) {
                            autoFormatDoseAmount("1", result.form)
                        } else {
                            state.doseAmount
                        },
                    )
                } else {
                    // No NDC match — pre-fill the search field with whatever was scanned.
                    state.copy(
                        isBarcodeScanning = false,
                        nameQuery = rawValue,
                        isSearching = rawValue.length >= 2,
                    )
                }
            }
            if (result == null && rawValue.length >= 2) {
                _searchTrigger.emit(rawValue)
            }
        }
    }

    private fun onSaveRequested() {
        val state = _uiState.value
        var hasError = false
        if (state.strength.isBlank()) {
            _uiState.update { it.copy(strengthError = true) }
            hasError = true
        }
        if (state.selectedForm == null) {
            _uiState.update { it.copy(formError = true) }
            hasError = true
        }
        if (state.startDate == null) {
            _uiState.update { it.copy(startDateError = true) }
            hasError = true
        }
        if (!validateScheduleInput(state.scheduleInput)) {
            _uiState.update { it.copy(scheduleValidationError = true) }
            hasError = true
        }
        val missWindowMinutes = state.missWindowMinutesText.toIntOrNull()?.takeIf { it in 5..360 }
        if (missWindowMinutes == null) {
            _uiState.update { it.copy(missWindowMinutesError = true) }
            hasError = true
        }
        if (hasError) return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val warnings = withContext(ioDispatcher) {
                repository.checkDuplicateIngredients(
                    newIngredientRxcuis = state.ingredientRxcuis,
                    excludeMedId = state.editMedId,
                    substituteForMedId = state.substituteForMedId,
                )
            }
            if (warnings.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        pendingDuplicateWarnings = warnings,
                        showDuplicateWarning = true,
                        isSaving = false,
                    )
                }
            } else {
                performSave()
            }
        }
    }

    private fun performSave() {
        _uiState.update { it.copy(showDuplicateWarning = false, isSaving = true) }
        val state = _uiState.value
        val missWindowMinutes = state.missWindowMinutesText.toIntOrNull()?.takeIf { it in 5..360 } ?: 60
        val nowMs = System.currentTimeMillis()

        val medId = state.editMedId ?: UUID.randomUUID().toString()
        val startDate = requireNotNull(state.startDate) { "startDate must not be null at save" }

        val entity = MedicationEntity(
            id = medId,
            name = state.displayName.trim(),
            rxcui = state.pendingDrug?.rxcui,
            ingredientsJson = repository.encodeIngredientsJson(state.ingredientRxcuis),
            strength = state.strength.trim(),
            form = requireNotNull(state.selectedForm) { "selectedForm must not be null at save" }.name,
            doseAmount = state.doseAmount.trim(),
            prescriber = state.prescriber.trim().ifEmpty { null },
            prescriberPhone = state.prescriberPhone.trim().ifEmpty { null },
            prescriberPractice = state.prescriberPractice.trim().ifEmpty { null },
            pharmacy = state.pharmacy.trim().ifEmpty { null },
            pharmacyPhone = state.pharmacyPhone.trim().ifEmpty { null },
            substituteForMedId = state.substituteForMedId,
            substituteNote = state.substituteNote.trim().ifEmpty { null },
            purpose = state.purpose.trim().ifEmpty { null },
            notes = state.notes.trim().ifEmpty { null },
            isFreeText = state.isFreeText,
            isCritical = state.isCritical,
            missWindowMinutes = missWindowMinutes,
            status = MedicationStatus.ACTIVE.name,
            startEpochMs = startDate
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            endEpochMs = state.endDate
                ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            // Preserve original createdAt when editing; set to now for new meds.
            createdAtEpochMs = state.originalCreatedAtMs ?: nowMs,
            updatedAtEpochMs = nowMs,
        )

        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    if (state.editMedId == null) repository.saveMedication(entity)
                    else repository.updateMedication(entity)

                    scheduleRepository.saveSchedule(
                        medId = medId,
                        input = state.scheduleInput,
                        startDate = startDate,
                        endDate = state.endDate,
                        zoneId = ZoneId.systemDefault(),
                    )
                }
            }.onSuccess {
                _uiState.update { it.copy(isSaving = false, savedSuccessfully = true) }
                // Auto-remember prescriber and pharmacy in the saved contacts store
                // (V1.3-F1). Fire-and-forget — a contact store failure never blocks save.
                val savedPrescriber = entity.prescriber
                val savedPharmacy = entity.pharmacy
                if (contactRepository != null && (savedPrescriber != null || savedPharmacy != null)) {
                    viewModelScope.launch(ioDispatcher) {
                        if (savedPrescriber != null) {
                            contactRepository.rememberPrescriber(savedPrescriber, entity.prescriberPhone, entity.prescriberPractice)
                        }
                        if (savedPharmacy != null) {
                            contactRepository.rememberPharmacy(savedPharmacy, entity.pharmacyPhone)
                        }
                    }
                }
                // Fire-and-forget: pre-populate the label cache so the reference screen loads fast
                // (spec §2.10 "fetched per-drug on add"). Silently skipped for free-text meds
                // (no rxcui) and when drugLabelRepository is not injected (tests).
                val rxcui = entity.rxcui
                if (rxcui != null && drugLabelRepository != null) {
                    viewModelScope.launch(ioDispatcher) {
                        drugLabelRepository.ensureLabelCached(rxcui)
                    }
                }
            }.onFailure {
                _uiState.update { it.copy(isSaving = false, saveError = SaveError.GENERAL) }
            }
        }
    }

    /**
     * Per-type schedule validation. Returns true if the input is valid enough to save.
     */
    private fun validateScheduleInput(input: ScheduleInput): Boolean = when (input.type) {
        ScheduleType.FIXED_DAILY, ScheduleType.TEMPORARY -> input.times.isNotEmpty()
        ScheduleType.DAYS_OF_WEEK -> input.times.isNotEmpty() && input.daysOfWeek.isNotEmpty()
        ScheduleType.INTERVAL -> input.intervalHours in 1..168
        ScheduleType.DOSE_WINDOW -> input.windowEnd.isAfter(input.windowStart)
        ScheduleType.PRN -> true
        ScheduleType.TAPER -> input.phases.isNotEmpty() && input.phases.all {
            it.doseDescription.isNotBlank() && it.durationDays > 0 && it.times.isNotEmpty()
        }
    }

    companion object {
        /**
         * If [form] is TABLET, CAPSULE, or PATCH and [input] is a bare number, appends the
         * appropriate singular or plural unit. Otherwise returns [input] unchanged.
         */
        fun autoFormatDoseAmount(input: String, form: MedicationForm?): String {
            if (form !in setOf(
                    MedicationForm.TABLET,
                    MedicationForm.CAPLET,
                    MedicationForm.CAPSULE,
                    MedicationForm.PATCH,
                )
            ) return input
            if (!input.matches(Regex("""^\d*\.?\d+$"""))) return input
            val number = input.toDoubleOrNull() ?: return input
            val unit = when (form) {
                MedicationForm.TABLET  -> if (number == 1.0) "tablet"  else "tablets"
                MedicationForm.CAPLET  -> if (number == 1.0) "caplet"  else "caplets"
                MedicationForm.CAPSULE -> if (number == 1.0) "capsule" else "capsules"
                MedicationForm.PATCH   -> if (number == 1.0) "patch"   else "patches"
                else -> return input
            }
            return "$input $unit"
        }

        fun factory(
            repository: MedicationRepository,
            scheduleRepository: ScheduleRepository,
            contactRepository: ContactRepository? = null,
            drugLabelRepository: DrugLabelRepository? = null,
            isDndAccessGranted: () -> Boolean = { true },
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AddEditMedicationViewModel(
                        repository = repository,
                        scheduleRepository = scheduleRepository,
                        contactRepository = contactRepository,
                        savedStateHandle = createSavedStateHandle(),
                        drugLabelRepository = drugLabelRepository,
                        isDndAccessGranted = isDndAccessGranted,
                    )
                }
            }
    }
}
