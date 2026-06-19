package com.beryndil.pharos.medication

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.data.drugref.DrugLabelRepository
import com.beryndil.pharos.data.medication.MedicationRepository
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.schedule.ScheduleRepository
import com.beryndil.pharos.schedule.ScheduleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalTime

@Immutable
data class MedicationListUiState(
    val medications: List<MedicationEntity> = emptyList(),
    val pendingPauseResumeEndMedId: String? = null,
    /** Non-null when the delete confirmation dialog should be shown. */
    val pendingDeleteMedId: String? = null,
    val pendingDeleteMedName: String? = null,
    /** medId → names of other meds that appear in this med's FDA interaction text. */
    val interactionAlerts: Map<String, List<String>> = emptyMap(),
    /** medIds for which a food interaction note exists in the cached FDA label. */
    val foodNoteMedIds: Set<String> = emptySet(),
    /** medId → active-schedule reminder info, for showing alarm time(s) on each row. */
    val scheduleInfo: Map<String, MedScheduleInfo> = emptyMap(),
)

/**
 * The reminder/alarm information for one medication's active schedule, surfaced on the list row.
 *
 * The raw schedule fields are exposed (not a pre-formatted string) so the UI layer can format
 * times with the device locale and resolve labels via string resources — matching the read-only
 * Medication Detail summary. Empty/null fields mean the schedule type has no wall-clock times.
 */
@Immutable
data class MedScheduleInfo(
    /** Stored [com.beryndil.pharos.data.regimen.entity.ScheduleType] name, or null if no schedule. */
    val scheduleType: String?,
    /** Wall-clock reminder times for fixed/day-of-week schedules. Empty for other types. */
    val scheduledTimes: List<LocalTime> = emptyList(),
    /** Interval in hours for interval schedules, or null. */
    val intervalHours: Int? = null,
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
    private val drugLabelRepository: DrugLabelRepository? = null,
) : ViewModel() {

    private val _deleteState = MutableStateFlow<Pair<String, String>?>(null)
    private val _drugAlerts = MutableStateFlow(Pair(emptyMap<String, List<String>>(), emptySet<String>()))
    private val _scheduleInfo = MutableStateFlow<Map<String, MedScheduleInfo>>(emptyMap())

    val uiState: StateFlow<MedicationListUiState> = combine(
        medicationRepository.observeAllMedications(),
        _deleteState,
        _drugAlerts,
        _scheduleInfo,
    ) { meds, del, (interactions, foodNotes), scheduleInfo ->
        MedicationListUiState(
            medications = meds,
            pendingDeleteMedId = del?.first,
            pendingDeleteMedName = del?.second,
            interactionAlerts = interactions,
            foodNoteMedIds = foodNotes,
            scheduleInfo = scheduleInfo,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MedicationListUiState(),
    )

    init {
        viewModelScope.launch {
            medicationRepository.observeAllMedications().collect { meds ->
                loadDrugAlerts(meds)
                loadScheduleInfo(meds)
            }
        }
    }

    /** Load each medication's active-schedule reminder info for the list row. */
    private suspend fun loadScheduleInfo(meds: List<MedicationEntity>) {
        val info = withContext(Dispatchers.IO) {
            meds.associate { med ->
                val schedule = scheduleRepository.getActiveSchedule(med.id)
                med.id to MedScheduleInfo(
                    scheduleType = schedule?.type,
                    scheduledTimes = schedule
                        ?.let { ScheduleEngine.parseTimes(it.scheduledTimesJson) }
                        ?: emptyList(),
                    intervalHours = schedule?.intervalHours,
                )
            }
        }
        _scheduleInfo.value = info
    }

    private suspend fun loadDrugAlerts(meds: List<MedicationEntity>) {
        val repo = drugLabelRepository ?: return
        val labels = withContext(Dispatchers.IO) {
            meds.mapNotNull { med ->
                val key = med.rxcui ?: return@mapNotNull null
                val label = repo.getCachedLabel(key) ?: return@mapNotNull null
                key to label
            }.toMap()
        }

        val interactions = mutableMapOf<String, MutableList<String>>()
        val foodNotes = mutableSetOf<String>()

        for (med in meds) {
            val key = med.rxcui ?: continue
            val label = labels[key] ?: continue
            if (!label.foodEffectText.isNullOrBlank()) foodNotes.add(med.id)
            val interactionsText = label.interactionsText?.lowercase() ?: continue
            for (other in meds) {
                if (other.id == med.id) continue
                val baseName = other.name.trim()
                    .replace(Regex("\\s+\\d.*"), "")
                    .replace(Regex("(?i)\\s+(ER|XR|SR|CR|XL|LA|DR|IR)\\b.*"), "")
                    .trim()
                    .lowercase()
                if (baseName.length >= 4 && interactionsText.contains(baseName)) {
                    interactions.getOrPut(med.id) { mutableListOf() }.add(other.name)
                }
            }
        }

        _drugAlerts.value = Pair(interactions, foodNotes)
    }

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
            drugLabelRepository: DrugLabelRepository? = null,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    MedicationListViewModel(
                        medicationRepository = medicationRepository,
                        scheduleRepository = scheduleRepository,
                        drugLabelRepository = drugLabelRepository,
                    )
                }
            }
    }
}
