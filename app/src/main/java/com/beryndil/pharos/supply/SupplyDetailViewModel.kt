package com.beryndil.pharos.supply

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.data.regimen.entity.SupplyRecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── UI state ──────────────────────────────────────────────────────────────────

@Immutable
data class SupplyDetailUiState(
    val loading: Boolean = true,
    val summary: SupplySummary? = null,
    val history: List<SupplyRecordEntity> = emptyList(),
    val dialogState: SupplyDialogState = SupplyDialogState.None,
    val error: String? = null,
)

sealed interface SupplyDialogState {
    data object None : SupplyDialogState
    data object LogUsage : SupplyDialogState
    data object LogRestock : SupplyDialogState
    data object Adjust : SupplyDialogState
    data object ConfirmEnd : SupplyDialogState
}

// ── Events ────────────────────────────────────────────────────────────────────

sealed interface SupplyDetailEvent {
    data object ShowLogUsageDialog : SupplyDetailEvent
    data object ShowLogRestockDialog : SupplyDetailEvent
    data object ShowAdjustDialog : SupplyDetailEvent
    data object ShowEndDialog : SupplyDetailEvent
    data object DismissDialog : SupplyDetailEvent

    data class ConfirmLogUsage(val quantity: Int, val notes: String?) : SupplyDetailEvent
    data class ConfirmLogRestock(val quantity: Int, val notes: String?) : SupplyDetailEvent
    data class ConfirmAdjust(val newQuantity: Int, val notes: String?) : SupplyDetailEvent
    data object ConfirmEnd : SupplyDetailEvent
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

class SupplyDetailViewModel(
    private val supplyRepository: SupplyRepository,
    private val supplyId: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _dialogState = MutableStateFlow<SupplyDialogState>(SupplyDialogState.None)
    private val _error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SupplyDetailUiState> = combine(
        supplyRepository.observeSupplySummary(supplyId),
        supplyRepository.observeRecords(supplyId),
        _dialogState,
        _error,
    ) { summary, records, dialog, error ->
        SupplyDetailUiState(
            loading = false,
            summary = summary,
            history = records,
            dialogState = dialog,
            error = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SupplyDetailUiState(loading = true),
    )

    fun onEvent(event: SupplyDetailEvent) {
        when (event) {
            SupplyDetailEvent.ShowLogUsageDialog -> _dialogState.update { SupplyDialogState.LogUsage }
            SupplyDetailEvent.ShowLogRestockDialog -> _dialogState.update { SupplyDialogState.LogRestock }
            SupplyDetailEvent.ShowAdjustDialog -> _dialogState.update { SupplyDialogState.Adjust }
            SupplyDetailEvent.ShowEndDialog -> _dialogState.update { SupplyDialogState.ConfirmEnd }
            SupplyDetailEvent.DismissDialog -> {
                _dialogState.update { SupplyDialogState.None }
                _error.update { null }
            }
            is SupplyDetailEvent.ConfirmLogUsage -> {
                _dialogState.update { SupplyDialogState.None }
                viewModelScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            supplyRepository.logUsage(supplyId, event.quantity, event.notes, clock())
                        }
                    }.onFailure { e -> _error.update { e.message } }
                }
            }
            is SupplyDetailEvent.ConfirmLogRestock -> {
                _dialogState.update { SupplyDialogState.None }
                viewModelScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            supplyRepository.logRestock(supplyId, event.quantity, event.notes, clock())
                        }
                    }.onFailure { e -> _error.update { e.message } }
                }
            }
            is SupplyDetailEvent.ConfirmAdjust -> {
                _dialogState.update { SupplyDialogState.None }
                viewModelScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            supplyRepository.logAdjustment(supplyId, event.newQuantity, event.notes, clock())
                        }
                    }.onFailure { e -> _error.update { e.message } }
                }
            }
            SupplyDetailEvent.ConfirmEnd -> {
                _dialogState.update { SupplyDialogState.None }
                viewModelScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { supplyRepository.endSupply(supplyId) }
                    }.onFailure { e -> _error.update { e.message } }
                }
            }
        }
    }

    companion object {
        fun factory(
            supplyRepository: SupplyRepository,
            supplyId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SupplyDetailViewModel(supplyRepository, supplyId) }
        }
    }
}
