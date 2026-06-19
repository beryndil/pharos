package com.beryndil.pharos.medication

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.core.debug.DebugLogger
import com.beryndil.pharos.data.dose.DoseRepository
import com.beryndil.pharos.data.drugref.DrugLabelRepository
import com.beryndil.pharos.data.medication.MedicationRepository
import com.beryndil.pharos.data.regimen.entity.DoseTransitionEntity
import com.beryndil.pharos.data.schedule.ScheduleRepository
import com.beryndil.pharos.schedule.ScheduleEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime

/**
 * State for the Medication Detail screen (read-only regimen summary + cached drug reference +
 * append-only dose history). No edit dialogs — edits happen on the Add/Edit screen reached via
 * the top-bar pencil (item #3).
 */
@Immutable
data class MedicationDetailUiState(
    /** True until the one-shot summary load resolves. */
    val isLoading: Boolean = true,
    /** Null while loading or if the medication could not be found. */
    val summary: RegimenSummary? = null,
    /** Cached openFDA label, or [LabelPreviewState.None] for free-text / no-cache meds. */
    val labelPreview: LabelPreviewState = LabelPreviewState.None,
    /** Source identifier for the cached label (Law 9). Null when no label. */
    val labelSource: String? = null,
    /** Fetch timestamp of the cached label (Law 9). Null when no label. */
    val labelFetchedAtEpochMs: Long? = null,
    /** True for free-text / no-rxcui meds — show the "no reference available" line (Law 3). */
    val isFreeText: Boolean = false,
    /** Append-only dose-transition history, newest first. */
    val transitions: List<DoseTransitionEntity> = emptyList(),
)

/** Read-only regimen summary block (the user's own data — never advice). */
@Immutable
data class RegimenSummary(
    val name: String,
    val strength: String,
    val formName: String,
    val doseAmount: String,
    /** Stored [com.beryndil.pharos.data.regimen.entity.MedicationStatus] name. */
    val status: String,
    /** Stored [com.beryndil.pharos.data.regimen.entity.ScheduleType] name, or null if no schedule. */
    val scheduleType: String?,
    /** Wall-clock alarm times for fixed/day-of-week schedules. Empty for other types. */
    val scheduledTimes: List<LocalTime> = emptyList(),
    /** Interval in hours for interval schedules, or null. */
    val intervalHours: Int? = null,
    /** Window open time (ISO HH:mm) for window schedules, or null. */
    val windowStartTime: String? = null,
    /** Window close time (ISO HH:mm) for window schedules, or null. */
    val windowEndTime: String? = null,
)

/**
 * Loads the read-only detail of a single medication: identity (MedicationRepository), active
 * schedule + alarm times (ScheduleRepository), cached drug label (DrugLabelRepository), and the
 * append-only dose-transition history (DoseRepository). The history is a live Flow; the rest is a
 * one-shot load (the summary screen is read-only, so nothing else needs continuous observation).
 */
class MedicationDetailViewModel(
    private val medicationId: String,
    private val medicationRepository: MedicationRepository,
    private val scheduleRepository: ScheduleRepository,
    private val drugLabelRepository: DrugLabelRepository,
    private val doseRepository: DoseRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val loaded = MutableStateFlow(MedicationDetailUiState())

    val uiState: StateFlow<MedicationDetailUiState> = combine(
        loaded,
        doseRepository.observeHistory(medicationId),
    ) { state, transitions ->
        state.copy(transitions = transitions)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MedicationDetailUiState(),
    )

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val med = withContext(ioDispatcher) { medicationRepository.getMedication(medicationId) }
                if (med == null) {
                    loaded.update { it.copy(isLoading = false, summary = null) }
                    return@launch
                }

                val schedule = withContext(ioDispatcher) {
                    scheduleRepository.getActiveSchedule(medicationId)
                }

                val summary = RegimenSummary(
                    name = med.name,
                    strength = med.strength,
                    formName = med.form,
                    doseAmount = med.doseAmount,
                    status = med.status,
                    scheduleType = schedule?.type,
                    scheduledTimes = schedule?.let { ScheduleEngine.parseTimes(it.scheduledTimesJson) }
                        ?: emptyList(),
                    intervalHours = schedule?.intervalHours,
                    windowStartTime = schedule?.windowStartTime,
                    windowEndTime = schedule?.windowEndTime,
                )

                loaded.update {
                    it.copy(
                        isLoading = false,
                        summary = summary,
                        isFreeText = med.isFreeText,
                    )
                }

                // Drug reference: cached label only — never a network fetch here (Law 4: read-only
                // detail surface). Free-text meds get no label reference (spec §2.11, Law 3).
                if (!med.isFreeText) {
                    val lookupKey = med.rxcui ?: "name:${med.name.trim().lowercase()}"
                    val label = withContext(ioDispatcher) {
                        try { drugLabelRepository.getCachedLabel(lookupKey) } catch (e: Exception) { null }
                    }
                    if (label != null) {
                        loaded.update {
                            it.copy(
                                labelPreview = LabelPreviewState.Available(
                                    boxedWarningText = label.boxedWarningText,
                                    sideEffectsText = label.sideEffectsText,
                                    interactionsText = label.interactionsText,
                                    warningsText = label.warningsText,
                                    precautionsText = label.precautionsText,
                                    foodEffectText = label.foodEffectText,
                                    brandName = label.brandName,
                                    source = label.source,
                                ),
                                labelSource = label.source,
                                labelFetchedAtEpochMs = label.fetchedAtEpochMs,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLogger.logError("MedicationDetail", "load failed", e)
                loaded.update { it.copy(isLoading = false) }
            }
        }
    }

    companion object {
        fun factory(
            medicationId: String,
            medicationRepository: MedicationRepository,
            scheduleRepository: ScheduleRepository,
            drugLabelRepository: DrugLabelRepository,
            doseRepository: DoseRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    MedicationDetailViewModel(
                        medicationId = medicationId,
                        medicationRepository = medicationRepository,
                        scheduleRepository = scheduleRepository,
                        drugLabelRepository = drugLabelRepository,
                        doseRepository = doseRepository,
                    )
                }
            }
    }
}
