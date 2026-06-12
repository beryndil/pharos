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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

@Immutable
data class MedicationListUiState(
    val medications: List<MedicationEntity> = emptyList(),
    val pendingPauseResumeEndMedId: String? = null,
)

sealed interface MedicationListEvent {
    data class PauseMedication(val medId: String) : MedicationListEvent
    data class ResumeMedication(val medId: String) : MedicationListEvent
    data class EndMedication(val medId: String) : MedicationListEvent
}

class MedicationListViewModel(
    private val medicationRepository: MedicationRepository,
    private val scheduleRepository: ScheduleRepository,
) : ViewModel() {

    val uiState: StateFlow<MedicationListUiState> = medicationRepository
        .observeActiveMedications()
        .map { meds -> MedicationListUiState(medications = meds) }
        .stateIn(
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
                        // Re-generate instances so the user gets upcoming doses
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
