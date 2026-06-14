package com.beryndil.pharos.medication

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.data.medication.MedicationRepository
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.schedule.ScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

@Immutable
data class MedicationListUiState(
    val medications: List<MedicationEntity> = emptyList(),
    val pendingPauseResumeEndMedId: String? = null,
    /** Non-null when the delete confirmation dialog should be shown. */
    val pendingDeleteMedId: String? = null,
    val pendingDeleteMedName: String? = null,
)

sealed interface MedicationListEvent {
    data class PauseMedication(val medId: String) : MedicationListEvent
    data class ResumeMedication(val medId: String) : MedicationListEvent
    data class EndMedication(val medId: String) : MedicationListEvent
    data class RequestDelete(val medId: String, val medName: String) : MedicationListEvent
    data object ConfirmDelete : MedicationListEvent
    data object CancelDelete : MedicationListEvent
}

class MedicationListViewModel(
    private val medicationRepository: MedicationRepository,
    private val scheduleRepository: ScheduleRepository,
) : ViewModel() {

    private val _deleteState = MutableStateFlow<Pair<String, String>?>(null)

    val uiState: StateFlow<MedicationListUiState> = combine(
        medicationRepository.observeAllMedications(),
        _deleteState,
    ) { meds, del ->
        MedicationListUiState(
            medications = meds,
            pendingDeleteMedId = del?.first,
            pendingDeleteMedName = del?.second,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MedicationListUiState(),
    )

    fun onEvent(event: MedicationListEvent) {
        when (event) {
            is MedicationListEvent.PauseMedication -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        medicationRepository.pauseMedication(event.medId)
                    }
                }
            }
            is MedicationListEvent.ResumeMedication -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        medicationRepository.resumeMedication(event.medId)
                        val from = Instant.now()
                        val to = from.plusMillis(90L * 86_400_000L)
                        scheduleRepository.generateInstancesForMed(event.medId, from, to)
                    }
                }
            }
            is MedicationListEvent.EndMedication -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        medicationRepository.endMedication(event.medId)
                    }
                }
            }
            is MedicationListEvent.RequestDelete -> {
                _deleteState.value = Pair(event.medId, event.medName)
            }
            is MedicationListEvent.CancelDelete -> {
                _deleteState.value = null
            }
            is MedicationListEvent.ConfirmDelete -> {
                val id = _deleteState.value?.first ?: return
                _deleteState.value = null
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        medicationRepository.deleteMedication(id)
                    }
                }
            }
        }
    }

    companion object {
        fun factory(
            medicationRepository: MedicationRepository,
            scheduleRepository: ScheduleRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    MedicationListViewModel(
                        medicationRepository = medicationRepository,
                        scheduleRepository = scheduleRepository,
                    )
                }
            }
    }
}
