package com.beryndil.pharos.reference

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.data.drugref.DrugLabelRepository
import com.beryndil.pharos.data.drugref.entity.LabelCacheEntity
import com.beryndil.pharos.data.regimen.dao.MedicationDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the drug-reference screen (spec §2.10, Law 3, Law 9).
 *
 * Loads the cached label for the medication's resolved RxCUI. If not yet cached, attempts a
 * network fetch (fire-and-updates-state approach).
 *
 * Law 3 framing: the screen surfaces label text as reference only; no advice, no severity
 * ratings, no "you should" language. The disclaimer string is part of the UI contract.
 */
class DrugReferenceViewModel(
    private val medicationId: String,
    private val medicationDao: MedicationDao,
    private val drugLabelRepository: DrugLabelRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DrugReferenceUiState>(DrugReferenceUiState.Loading)
    val uiState: StateFlow<DrugReferenceUiState> = _uiState.asStateFlow()

    init {
        loadReference()
    }

    private fun loadReference() {
        viewModelScope.launch {
            val med = withContext(Dispatchers.IO) { medicationDao.getById(medicationId) }
            if (med == null) {
                _uiState.value = DrugReferenceUiState.NotAvailableOffline("") // edge case
                return@launch
            }
            // Free-text meds get no label reference (spec §2.11).
            if (med.isFreeText || med.rxcui == null) {
                _uiState.value = DrugReferenceUiState.FreeTextMed(medName = med.name)
                return@launch
            }

            // Check cache immediately (no network round-trip for the first render).
            val cached = withContext(Dispatchers.IO) {
                drugLabelRepository.getCachedLabel(med.rxcui)
            }
            if (cached != null) {
                _uiState.value = cached.toLoaded(med.name, isFetching = false)
                return@launch
            }

            // Not yet cached — show loading state while we fetch.
            _uiState.value = DrugReferenceUiState.Loading

            val fetched = withContext(Dispatchers.IO) {
                drugLabelRepository.getOrFetchLabel(med.rxcui)
            }
            _uiState.value = if (fetched != null) {
                fetched.toLoaded(med.name, isFetching = false)
            } else {
                DrugReferenceUiState.NotAvailableOffline(medName = med.name)
            }
        }
    }

    companion object {
        fun factory(
            medicationId: String,
            medicationDao: MedicationDao,
            drugLabelRepository: DrugLabelRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DrugReferenceViewModel(
                    medicationId = medicationId,
                    medicationDao = medicationDao,
                    drugLabelRepository = drugLabelRepository,
                )
            }
        }
    }
}

/** All possible render states for the drug reference screen. */
@Immutable
sealed interface DrugReferenceUiState {

    /** Initial / mid-fetch state. */
    data object Loading : DrugReferenceUiState

    /**
     * Label data is available from the cache. [sideEffectsText] and [interactionsText] may each
     * be null if that section was absent in the source database (shown as "not available").
     */
    data class Loaded(
        val medName: String,
        val sideEffectsText: String?,
        val interactionsText: String?,
        /** Human-readable source (e.g., "openFDA"). Displayed per Law 9. */
        val source: String,
        /** UTC epoch-ms of the fetch. Displayed as freshness date per Law 9. */
        val fetchedAtEpochMs: Long,
        /** True while a first-time network fetch is in progress and no cache exists yet. */
        val isFetching: Boolean,
    ) : DrugReferenceUiState

    /**
     * Medication was added as free-text / without RxNorm resolution — no label reference
     * is available (spec §2.11). UI says so plainly.
     */
    data class FreeTextMed(val medName: String) : DrugReferenceUiState

    /**
     * Reference not yet cached AND the network fetch failed (offline or source has no data).
     * UI says "reference not available offline" rather than showing nothing (spec §2.10).
     */
    data class NotAvailableOffline(val medName: String) : DrugReferenceUiState
}

private fun LabelCacheEntity.toLoaded(medName: String, isFetching: Boolean) =
    DrugReferenceUiState.Loaded(
        medName = medName,
        sideEffectsText = sideEffectsText,
        interactionsText = interactionsText,
        source = source,
        fetchedAtEpochMs = fetchedAtEpochMs,
        isFetching = isFetching,
    )
