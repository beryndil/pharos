package com.beryndil.pharos.medication

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.data.medication.MedicationRepository
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.medication.model.DrugSearchResult
import com.beryndil.pharos.medication.model.DuplicateWarning
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
    val pharmacy: String = "",
    val purpose: String = "",

    /** Preserved from the existing entity so edit mode doesn't clobber createdAt. */
    val originalCreatedAtMs: Long? = null,

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
    data class PharmacyChanged(val value: String) : AddEditMedEvent
    data class PurposeChanged(val value: String) : AddEditMedEvent
    data object SaveRequested : AddEditMedEvent
    data object DuplicateWarningConfirmed : AddEditMedEvent
    data object DuplicateWarningDismissed : AddEditMedEvent
    data object ErrorDismissed : AddEditMedEvent
}

@OptIn(FlowPreview::class)
class AddEditMedicationViewModel(
    private val repository: MedicationRepository,
    savedStateHandle: SavedStateHandle,
    /**
     * IO dispatcher injected for testability — production code always passes [Dispatchers.IO];
     * tests pass an [UnconfinedTestDispatcher] so all work runs synchronously.
     */
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddEditMedicationUiState())
    val uiState: StateFlow<AddEditMedicationUiState> = _uiState.asStateFlow()

    private val _searchTrigger = MutableSharedFlow<String>(replay = 1)

    init {
        val editMedId: String? = savedStateHandle["medId"]
        if (editMedId != null) {
            _uiState.update { it.copy(editMedId = editMedId) }
            loadExistingMedication(editMedId)
        }
        startSearchDebounce()
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
                _uiState.update { it.copy(selectedForm = event.form, formError = false) }
            is AddEditMedEvent.DoseAmountChanged ->
                _uiState.update { it.copy(doseAmount = event.value) }
            is AddEditMedEvent.StartDateSelected ->
                _uiState.update { it.copy(startDate = event.date, startDateError = false) }
            is AddEditMedEvent.EndDateSelected ->
                _uiState.update { it.copy(endDate = event.date) }
            is AddEditMedEvent.PrescriberChanged ->
                _uiState.update { it.copy(prescriber = event.value) }
            is AddEditMedEvent.PharmacyChanged ->
                _uiState.update { it.copy(pharmacy = event.value) }
            is AddEditMedEvent.PurposeChanged ->
                _uiState.update { it.copy(purpose = event.value) }
            is AddEditMedEvent.SaveRequested -> onSaveRequested()
            is AddEditMedEvent.DuplicateWarningConfirmed -> performSave()
            is AddEditMedEvent.DuplicateWarningDismissed ->
                _uiState.update { it.copy(showDuplicateWarning = false, isSaving = false) }
            is AddEditMedEvent.ErrorDismissed ->
                _uiState.update { it.copy(saveError = null) }
        }
    }

    // ── Private ───────────────────────────────────────────────────────────

    private fun loadExistingMedication(medId: String) {
        viewModelScope.launch {
            val med = withContext(ioDispatcher) { repository.getMedication(medId) }
                ?: return@launch
            val ingredientRxcuis = repository.parseIngredientRxcuis(med.ingredientsJson)
            val ingredientNames = withContext(ioDispatcher) {
                repository.getIngredientNames(ingredientRxcuis)
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
                    pharmacy = med.pharmacy ?: "",
                    purpose = med.purpose ?: "",
                    originalCreatedAtMs = med.createdAtEpochMs,
                )
            }
        }
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
        val mappedForm = repository.mapRxNormForm(drug.rxNormForm)
        _uiState.update {
            it.copy(
                step = FormStep.DETAILS,
                isFreeText = false,
                displayName = drug.name,
                ingredientNames = drug.ingredientNames,
                ingredientRxcuis = drug.ingredientRxcuis,
                // Pre-fill strength and form from RxNorm; user can still change them.
                strength = drug.strength.ifEmpty { it.strength },
                selectedForm = if (mappedForm != MedicationForm.OTHER) mappedForm
                    else it.selectedForm,
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
        if (hasError) return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val warnings = withContext(ioDispatcher) {
                repository.checkDuplicateIngredients(
                    newIngredientRxcuis = state.ingredientRxcuis,
                    excludeMedId = state.editMedId,
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
        val nowMs = System.currentTimeMillis()

        val entity = MedicationEntity(
            id = state.editMedId ?: UUID.randomUUID().toString(),
            name = state.displayName.trim(),
            rxcui = state.pendingDrug?.rxcui,
            ingredientsJson = repository.encodeIngredientsJson(state.ingredientRxcuis),
            strength = state.strength.trim(),
            form = requireNotNull(state.selectedForm) { "selectedForm must not be null at save" }.name,
            doseAmount = state.doseAmount.trim(),
            prescriber = state.prescriber.trim().ifEmpty { null },
            pharmacy = state.pharmacy.trim().ifEmpty { null },
            purpose = state.purpose.trim().ifEmpty { null },
            isFreeText = state.isFreeText,
            status = MedicationStatus.ACTIVE.name,
            startEpochMs = requireNotNull(state.startDate) { "startDate must not be null at save" }
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
                }
            }.onSuccess {
                _uiState.update { it.copy(isSaving = false, savedSuccessfully = true) }
            }.onFailure {
                _uiState.update { it.copy(isSaving = false, saveError = SaveError.GENERAL) }
            }
        }
    }

    companion object {
        fun factory(repository: MedicationRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AddEditMedicationViewModel(
                        repository = repository,
                        savedStateHandle = createSavedStateHandle(),
                    )
                }
            }
    }
}
