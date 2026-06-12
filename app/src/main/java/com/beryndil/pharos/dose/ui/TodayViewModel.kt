package com.beryndil.pharos.dose.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.data.dose.DoseRepository
import com.beryndil.pharos.data.dose.DoseRow
import com.beryndil.pharos.data.dose.PrnMedRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class TodayUiState(
    val doses: List<DoseRow> = emptyList(),
    val prnMeds: List<PrnMedRow> = emptyList(),
    /**
     * Non-null when a PRN log triggered the daily-max advisory (spec §2.7, Law 3).
     * Holds (doseNumber, dailyMax). The UI formats the user-facing string from these values.
     * Advisory only — never blocks or forbids the dose (already logged before this is set).
     */
    val prnWarningDoseNumber: Int? = null,
    val prnWarningDailyMax: Int = 0,
)

sealed interface TodayEvent {
    data class Take(val doseId: String) : TodayEvent
    data class Snooze(val doseId: String) : TodayEvent
    data class Skip(val doseId: String) : TodayEvent

    /**
     * User tapped "Log dose" for a PRN medication (spec §2.7).
     * The dose is logged immediately; [DoseRepository.logPrn] returns a [PrnLogResult]
     * whose [exceedsMax] field drives the optional daily-max advisory.
     */
    data class LogPrn(val medicationId: String, val scheduleId: String) : TodayEvent

    /** Dismiss the PRN daily-max advisory dialog (advisory only, Law 3). */
    data object DismissPrnWarning : TodayEvent
}

/** Drives the today/upcoming dose surface (Slice 5). Actions route to the dose state machine. */
class TodayViewModel(private val doseRepository: DoseRepository) : ViewModel() {

    /** Non-null while the PRN daily-max advisory should be shown. */
    private val _prnWarning = MutableStateFlow<Pair<Int, Int>?>(null)

    val uiState: StateFlow<TodayUiState> = combine(
        doseRepository.observeTodayDoses(),
        doseRepository.observePrnMeds(),
        _prnWarning,
    ) { doses, prnMeds, warning ->
        TodayUiState(
            doses = doses,
            prnMeds = prnMeds,
            prnWarningDoseNumber = warning?.first,
            prnWarningDailyMax = warning?.second ?: 0,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TodayUiState(),
    )

    fun onEvent(event: TodayEvent) {
        // DismissPrnWarning is a pure-state update; handle without coroutine.
        if (event is TodayEvent.DismissPrnWarning) {
            _prnWarning.value = null
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                when (event) {
                    is TodayEvent.Take -> doseRepository.take(event.doseId)
                    is TodayEvent.Snooze -> doseRepository.snooze(event.doseId)
                    is TodayEvent.Skip -> doseRepository.skip(event.doseId)
                    is TodayEvent.LogPrn -> {
                        val result = doseRepository.logPrn(event.medicationId, event.scheduleId)
                        if (result.exceedsMax && result.dailyMax != null) {
                            _prnWarning.value = Pair(result.doseNumber, result.dailyMax)
                        }
                    }
                    // Already handled above — unreachable but needed for exhaustive when.
                    is TodayEvent.DismissPrnWarning -> Unit
                }
            }
        }
    }

    companion object {
        fun factory(doseRepository: DoseRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { TodayViewModel(doseRepository = doseRepository) }
            }
    }
}
