package com.beryndil.pharos.medication

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.data.medication.MedicationRepository
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Immutable
data class MedicationListUiState(
    val medications: List<MedicationEntity> = emptyList(),
)

class MedicationListViewModel(
    repository: MedicationRepository,
) : ViewModel() {

    val uiState: StateFlow<MedicationListUiState> = repository
        .observeActiveMedications()
        .map { meds -> MedicationListUiState(medications = meds) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MedicationListUiState(),
        )

    companion object {
        fun factory(repository: MedicationRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { MedicationListViewModel(repository) }
            }
    }
}
