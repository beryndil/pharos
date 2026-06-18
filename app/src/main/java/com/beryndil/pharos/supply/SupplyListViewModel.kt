package com.beryndil.pharos.supply

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Immutable
data class SupplyListUiState(
    val loading: Boolean = true,
    val supplies: List<SupplySummary> = emptyList(),
)

class SupplyListViewModel(
    supplyRepository: SupplyRepository,
) : ViewModel() {

    val uiState: StateFlow<SupplyListUiState> =
        supplyRepository.observeAllSummaries()
            .map { SupplyListUiState(loading = false, supplies = it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SupplyListUiState(loading = true),
            )

    companion object {
        fun factory(supplyRepository: SupplyRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { SupplyListViewModel(supplyRepository) }
            }
    }
}
