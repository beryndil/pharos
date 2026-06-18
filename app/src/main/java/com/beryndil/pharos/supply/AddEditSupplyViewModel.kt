package com.beryndil.pharos.supply

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
    val pharmacyName: String = "",
    val pharmacyPhone: String = "",
    val lowThreshold: String = "",
    val notes: String = "",
    /** Only shown when adding a new supply. */
    val initialCount: String = "",
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
    data class PharmacyNameChanged(val value: String) : AddEditSupplyEvent
    data class PharmacyPhoneChanged(val value: String) : AddEditSupplyEvent
    data class LowThresholdChanged(val value: String) : AddEditSupplyEvent
    data class NotesChanged(val value: String) : AddEditSupplyEvent
    data class InitialCountChanged(val value: String) : AddEditSupplyEvent
    data object Save : AddEditSupplyEvent
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

class AddEditSupplyViewModel(
    private val supplyRepository: SupplyRepository,
    private val editSupplyId: String? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddEditSupplyUiState())
    val uiState: StateFlow<AddEditSupplyUiState> = _uiState.asStateFlow()

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
        }
    }

    fun onEvent(event: AddEditSupplyEvent) {
        when (event) {
            is AddEditSupplyEvent.NameChanged -> _uiState.update { it.copy(name = event.value) }
            is AddEditSupplyEvent.UnitChanged -> _uiState.update { it.copy(unit = event.value) }
            is AddEditSupplyEvent.PrescriberNameChanged -> _uiState.update { it.copy(prescriberName = event.value) }
            is AddEditSupplyEvent.PrescriberPhoneChanged -> _uiState.update { it.copy(prescriberPhone = event.value) }
            is AddEditSupplyEvent.PharmacyNameChanged -> _uiState.update { it.copy(pharmacyName = event.value) }
            is AddEditSupplyEvent.PharmacyPhoneChanged -> _uiState.update { it.copy(pharmacyPhone = event.value) }
            is AddEditSupplyEvent.LowThresholdChanged -> _uiState.update { it.copy(lowThreshold = event.value) }
            is AddEditSupplyEvent.NotesChanged -> _uiState.update { it.copy(notes = event.value) }
            is AddEditSupplyEvent.InitialCountChanged -> _uiState.update { it.copy(initialCount = event.value) }
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

    companion object {
        fun factory(
            supplyRepository: SupplyRepository,
            editSupplyId: String? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { AddEditSupplyViewModel(supplyRepository, editSupplyId) }
        }
    }
}
