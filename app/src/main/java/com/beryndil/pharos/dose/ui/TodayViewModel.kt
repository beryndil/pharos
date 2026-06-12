package com.beryndil.pharos.dose.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.data.dose.DoseRepository
import com.beryndil.pharos.data.dose.DoseRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class TodayUiState(val doses: List<DoseRow> = emptyList())

sealed interface TodayEvent {
    data class Take(val doseId: String) : TodayEvent
    data class Snooze(val doseId: String) : TodayEvent
    data class Skip(val doseId: String) : TodayEvent
}

/** Drives the today/upcoming dose surface (Slice 5). Actions route to the dose state machine. */
class TodayViewModel(private val doseRepository: DoseRepository) : ViewModel() {

    val uiState: StateFlow<TodayUiState> = doseRepository
        .observeTodayDoses()
        .map { TodayUiState(doses = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TodayUiState(),
        )

    fun onEvent(event: TodayEvent) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                when (event) {
                    is TodayEvent.Take -> doseRepository.take(event.doseId)
                    is TodayEvent.Snooze -> doseRepository.snooze(event.doseId)
                    is TodayEvent.Skip -> doseRepository.skip(event.doseId)
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
