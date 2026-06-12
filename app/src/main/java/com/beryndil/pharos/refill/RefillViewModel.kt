package com.beryndil.pharos.refill

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.data.regimen.entity.RefillRecordEntity
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
data class RefillUiState(
    val loading: Boolean = true,
    val summary: RefillSummary? = null,
    val history: List<RefillRecordEntity> = emptyList(),
    val dialogState: RefillDialogState = RefillDialogState.None,
    val error: String? = null,
)

sealed interface RefillDialogState {
    data object None : RefillDialogState
    data object SetInitialCount : RefillDialogState
    data object PickupRefill : RefillDialogState
    data object PartialFill : RefillDialogState
    data object ConfirmStopBeforeEmpty : RefillDialogState
    data object SetRefillByDate : RefillDialogState
}

// ── Events ────────────────────────────────────────────────────────────────────

sealed interface RefillEvent {
    data object ShowSetInitialCountDialog : RefillEvent
    data object ShowPickupDialog : RefillEvent
    data object ShowPartialFillDialog : RefillEvent
    data object ShowStopBeforeEmptyDialog : RefillEvent
    data object ShowSetRefillByDialog : RefillEvent
    data object DismissDialog : RefillEvent

    data class ConfirmSetInitialCount(
        val quantity: Int,
        val unit: String,
        val pharmacyPhone: String?,
    ) : RefillEvent

    data class ConfirmPickup(
        val newQuantity: Int,
        val unit: String,
        val pharmacyPhone: String?,
        val notes: String?,
        val refillByEpochMs: Long?,
    ) : RefillEvent

    data class ConfirmPartialFill(
        val additionalQuantity: Int,
        val unit: String,
        val notes: String?,
    ) : RefillEvent

    data object ConfirmStopBeforeEmpty : RefillEvent

    data class ConfirmSetRefillByDate(val epochMs: Long) : RefillEvent
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

class RefillViewModel(
    private val refillRepository: RefillRepository,
    private val medicationId: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val dialogState = MutableStateFlow<RefillDialogState>(RefillDialogState.None)

    val uiState: StateFlow<RefillUiState> = combine(
        refillRepository.observeRefillSummary(medicationId),
        refillRepository.observeRefillHistory(medicationId),
        dialogState,
    ) { summary, history, dialog ->
        RefillUiState(
            loading = false,
            summary = summary,
            history = history,
            dialogState = dialog,
            error = null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RefillUiState(loading = true),
    )

    fun onEvent(event: RefillEvent) {
        when (event) {
            RefillEvent.ShowSetInitialCountDialog ->
                dialogState.update { RefillDialogState.SetInitialCount }
            RefillEvent.ShowPickupDialog ->
                dialogState.update { RefillDialogState.PickupRefill }
            RefillEvent.ShowPartialFillDialog ->
                dialogState.update { RefillDialogState.PartialFill }
            RefillEvent.ShowStopBeforeEmptyDialog ->
                dialogState.update { RefillDialogState.ConfirmStopBeforeEmpty }
            RefillEvent.ShowSetRefillByDialog ->
                dialogState.update { RefillDialogState.SetRefillByDate }
            RefillEvent.DismissDialog ->
                dialogState.update { RefillDialogState.None }

            is RefillEvent.ConfirmSetInitialCount -> {
                dialogState.update { RefillDialogState.None }
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        refillRepository.setInitialCount(
                            medicationId = medicationId,
                            quantity = event.quantity,
                            unit = event.unit,
                            pharmacyPhone = event.pharmacyPhone,
                            nowMs = clock(),
                        )
                    }
                }
            }

            is RefillEvent.ConfirmPickup -> {
                dialogState.update { RefillDialogState.None }
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        refillRepository.recordPickup(
                            medicationId = medicationId,
                            newQuantity = event.newQuantity,
                            unit = event.unit,
                            pharmacyPhone = event.pharmacyPhone,
                            notes = event.notes,
                            refillByEpochMs = event.refillByEpochMs,
                            nowMs = clock(),
                        )
                    }
                }
            }

            is RefillEvent.ConfirmPartialFill -> {
                dialogState.update { RefillDialogState.None }
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        refillRepository.recordPartialFill(
                            medicationId = medicationId,
                            additionalQuantity = event.additionalQuantity,
                            unit = event.unit,
                            notes = event.notes,
                            nowMs = clock(),
                        )
                    }
                }
            }

            RefillEvent.ConfirmStopBeforeEmpty -> {
                dialogState.update { RefillDialogState.None }
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        refillRepository.recordStoppedBeforeEmpty(
                            medicationId = medicationId,
                            nowMs = clock(),
                        )
                    }
                }
            }

            is RefillEvent.ConfirmSetRefillByDate -> {
                dialogState.update { RefillDialogState.None }
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        refillRepository.setRefillByDate(
                            medicationId = medicationId,
                            refillByEpochMs = event.epochMs,
                            nowMs = clock(),
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun factory(
            refillRepository: RefillRepository,
            medicationId: String,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    RefillViewModel(
                        refillRepository = refillRepository,
                        medicationId = medicationId,
                    )
                }
            }
    }
}
