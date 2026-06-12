package com.beryndil.pharos.dose.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.data.dose.DoseRepository
import com.beryndil.pharos.data.regimen.entity.DoseTransitionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class DoseHistoryUiState(
    val medicationName: String = "",
    val transitions: List<DoseTransitionEntity> = emptyList(),
)

/** Read-only per-med append-only dose-transition history (Slice 5, Law 9). */
class DoseHistoryViewModel(
    private val doseRepository: DoseRepository,
    private val medicationId: String,
) : ViewModel() {

    private val medName = MutableStateFlow("")

    val uiState: StateFlow<DoseHistoryUiState> = combine(
        doseRepository.observeHistory(medicationId),
        medName,
    ) { transitions, name ->
        DoseHistoryUiState(medicationName = name, transitions = transitions)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DoseHistoryUiState(),
    )

    init {
        viewModelScope.launch {
            medName.update { doseRepository.medicationName(medicationId) }
        }
    }

    companion object {
        fun factory(
            doseRepository: DoseRepository,
            medicationId: String,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    DoseHistoryViewModel(
                        doseRepository = doseRepository,
                        medicationId = medicationId,
                    )
                }
            }
    }
}
