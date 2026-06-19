package com.beryndil.pharos.supply

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.contacts.ContactRepository
import com.beryndil.pharos.data.regimen.entity.PharmacyEntity
import com.beryndil.pharos.data.regimen.entity.PrescriberEntity
import com.beryndil.pharos.data.regimen.entity.SupplyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── UI state ──────────────────────────────────────────────────────────────────

@Immutable
data class AddEditSupplyUiState(
    val loading: Boolean = true,
    val name: String = "",
    val unit: String = "",
    val prescriberName: String = "",
    val prescriberPhone: String = "",
    val prescriberPractice: String = "",
    val pharmacyName: String = "",
    val pharmacyPhone: String = "",
    val lowThreshold: String = "",
    val notes: String = "",
    /** Only shown when adding a new supply. */
    val initialCount: String = "",
    val prescriberSuggestions: List<PrescriberEntity> = emptyList(),
    val pharmacySuggestions: List<PharmacyEntity> = emptyList(),
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

// ── Events ────────────────────────────────────────────────────────────────────

sealed interface AddEditSupplyEvent {
    data class NameChanged(val value: String) : AddEditSupplyEvent
    data class UnitChanged(val value: String) : AddEditSupplyEvent
    data class PrescriberNameChanged(val value: String) : AddEditSupplyEvent
    data class PrescriberPhoneChanged(val value: String) : AddEditSupplyEvent
    data class PrescriberPracticeChanged(val value: String) : AddEditSupplyEvent
    data class PrescriberSuggestionPicked(val prescriber: PrescriberEntity) : AddEditSupplyEvent
    data class PharmacyNameChanged(val value: String) : AddEditSupplyEvent
    data class PharmacyPhoneChanged(val value: String) : AddEditSupplyEvent
    data class PharmacySuggestionPicked(val pharmacy: PharmacyEntity) : AddEditSupplyEvent
    data class LowThresholdChanged(val value: String) : AddEditSupplyEvent
    data class NotesChanged(val value: String) : AddEditSupplyEvent
    data class InitialCountChanged(val value: String) : AddEditSupplyEvent
    data object Save : AddEditSupplyEvent
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

class AddEditSupplyViewModel(
    private val supplyRepository: SupplyRepository,
    private val contactRepository: ContactRepository,
    private val editSupplyId: String? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddEditSupplyUiState())
    val uiState: StateFlow<AddEditSupplyUiState> = _uiState.asStateFlow()

    // Cached full lists for synchronous filtering on each keystroke.
    private val _allPrescribers = MutableStateFlow<List<PrescriberEntity>>(emptyList())
    private val _allPharmacies = MutableStateFlow<List<PharmacyEntity>>(emptyList())

    init {
        if (editSupplyId != null) {
            viewModelScope.launch {
                val supply = withContext(Dispatchers.IO) { supplyRepository.getById(editSupplyId) }
                if (supply != null) {
                    _uiState.update { _ ->
                        AddEditSupplyUiState(
                            loading = false,
                            name = supply.name,
                            unit = supply.unit,
                            prescriberName = supply.prescriberName.orEmpty(),
                            prescriberPhone = supply.prescriberPhone.orEmpty(),
                            pharmacyName = supply.pharmacyName.orEmpty(),
                            pharmacyPhone = supply.pharmacyPhone.orEmpty(),
                            lowThreshold = if (supply.lowThreshold > 0) supply.lowThreshold.toString() else "",
                            notes = supply.notes.orEmpty(),
                            isEditing = true,
                        )
                    }
                } else {
                    _uiState.update { it.copy(loading = false, error = "Supply not found.") }
                }
            }
        } else {
            _uiState.update { it.copy(loading = false) }
            loadDefaultContacts()
        }
        startSuggestionCollection()
    }

    private fun loadDefaultContacts() {
        viewModelScope.launch {
            val prescriber = withContext(Dispatchers.IO) { contactRepository.getDefaultPrescriber() }
            val pharmacy = withContext(Dispatchers.IO) { contactRepository.getDefaultPharmacy() }
            if (prescriber == null && pharmacy == null) return@launch
            _uiState.update { state ->
                state.copy(
                    prescriberName = prescriber?.name ?: state.prescriberName,
                    prescriberPhone = prescriber?.phone ?: state.prescriberPhone,
                    prescriberPractice = prescriber?.practice ?: state.prescriberPractice,
                    pharmacyName = pharmacy?.name ?: state.pharmacyName,
                    pharmacyPhone = pharmacy?.phone ?: state.pharmacyPhone,
                )
            }
        }
    }

    private fun startSuggestionCollection() {
        viewModelScope.launch {
            contactRepository.observePrescribers().collect { all ->
                _allPrescribers.value = all
                _uiState.update { state ->
                    state.copy(prescriberSuggestions = filterPrescribers(all, state.prescriberName))
                }
            }
        }
        viewModelScope.launch {
            contactRepository.observePharmacies().collect { all ->
                _allPharmacies.value = all
                _uiState.update { state ->
                    state.copy(pharmacySuggestions = filterPharmacies(all, state.pharmacyName))
                }
            }
        }
    }

    fun onEvent(event: AddEditSupplyEvent) {
        when (event) {
            is AddEditSupplyEvent.NameChanged ->
                _uiState.update { it.copy(name = event.value) }
            is AddEditSupplyEvent.UnitChanged ->
                _uiState.update { it.copy(unit = event.value) }
            is AddEditSupplyEvent.PrescriberNameChanged ->
                _uiState.update { state ->
                    state.copy(
                        prescriberName = event.value,
                        prescriberSuggestions = filterPrescribers(_allPrescribers.value, event.value),
                    )
                }
            is AddEditSupplyEvent.PrescriberPhoneChanged ->
                _uiState.update { it.copy(prescriberPhone = event.value) }
            is AddEditSupplyEvent.PrescriberPracticeChanged ->
                _uiState.update { it.copy(prescriberPractice = event.value) }
            is AddEditSupplyEvent.PrescriberSuggestionPicked ->
                _uiState.update {
                    it.copy(
                        prescriberName = event.prescriber.name,
                        prescriberPhone = (event.prescriber.phone ?: it.prescriberPhone)
                            .filter { c -> c.isDigit() }.take(10),
                        prescriberPractice = event.prescriber.practice ?: it.prescriberPractice,
                        prescriberSuggestions = emptyList(),
                    )
                }
            is AddEditSupplyEvent.PharmacyNameChanged ->
                _uiState.update { state ->
                    state.copy(
                        pharmacyName = event.value,
                        pharmacySuggestions = filterPharmacies(_allPharmacies.value, event.value),
                    )
                }
            is AddEditSupplyEvent.PharmacyPhoneChanged ->
                _uiState.update { it.copy(pharmacyPhone = event.value) }
            is AddEditSupplyEvent.PharmacySuggestionPicked ->
                _uiState.update {
                    it.copy(
                        pharmacyName = event.pharmacy.name,
                        pharmacyPhone = (event.pharmacy.phone ?: it.pharmacyPhone)
                            .filter { c -> c.isDigit() }.take(10),
                        pharmacySuggestions = emptyList(),
                    )
                }
            is AddEditSupplyEvent.LowThresholdChanged ->
                _uiState.update { it.copy(lowThreshold = event.value) }
            is AddEditSupplyEvent.NotesChanged ->
                _uiState.update { it.copy(notes = event.value) }
            is AddEditSupplyEvent.InitialCountChanged ->
                _uiState.update { it.copy(initialCount = event.value) }
            is AddEditSupplyEvent.Save -> save()
        }
    }

    private fun save() {
        val state = _uiState.value
        val name = state.name.trim()
        val unit = state.unit.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(error = "Supply name is required.") }
            return
        }
        if (unit.isBlank()) {
            _uiState.update { it.copy(error = "Unit is required (e.g. sensors, pods, needles).") }
            return
        }
        val threshold = state.lowThreshold.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val initialCount = state.initialCount.trim().toIntOrNull()?.coerceAtLeast(0)

        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (editSupplyId != null) {
                        val existing = supplyRepository.getById(editSupplyId)
                            ?: error("Supply not found")
                        supplyRepository.updateSupply(
                            existing.copy(
                                name = name,
                                unit = unit,
                                prescriberName = state.prescriberName.trim().ifBlank { null },
                                prescriberPhone = state.prescriberPhone.trim().ifBlank { null },
                                pharmacyName = state.pharmacyName.trim().ifBlank { null },
                                pharmacyPhone = state.pharmacyPhone.trim().ifBlank { null },
                                lowThreshold = threshold,
                                notes = state.notes.trim().ifBlank { null },
                            ),
                        )
                    } else {
                        supplyRepository.addSupply(
                            name = name,
                            unit = unit,
                            prescriberName = state.prescriberName.trim().ifBlank { null },
                            prescriberPhone = state.prescriberPhone.trim().ifBlank { null },
                            pharmacyName = state.pharmacyName.trim().ifBlank { null },
                            pharmacyPhone = state.pharmacyPhone.trim().ifBlank { null },
                            lowThreshold = threshold,
                            notes = state.notes.trim().ifBlank { null },
                            initialCount = initialCount,
                            nowMs = clock(),
                        )
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(isSaving = false, saved = true) }
            }.onFailure { e ->
                _uiState.update { it.copy(isSaving = false, error = e.message ?: "Save failed.") }
            }
        }
    }

    private fun filterPrescribers(all: List<PrescriberEntity>, query: String): List<PrescriberEntity> =
        if (query.isBlank()) emptyList()
        else all.filter {
            it.name.contains(query, ignoreCase = true) && !it.name.equals(query, ignoreCase = true)
        }

    private fun filterPharmacies(all: List<PharmacyEntity>, query: String): List<PharmacyEntity> =
        if (query.isBlank()) emptyList()
        else all.filter {
            it.name.contains(query, ignoreCase = true) && !it.name.equals(query, ignoreCase = true)
        }

    companion object {
        fun factory(
            supplyRepository: SupplyRepository,
            contactRepository: ContactRepository,
            editSupplyId: String? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AddEditSupplyViewModel(supplyRepository, contactRepository, editSupplyId)
            }
        }
    }
}
