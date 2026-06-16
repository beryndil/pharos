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
 * network fetch. The user can force a refresh via [refresh] to clear the cache and re-fetch.
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

    private var cachedRxcui: String? = null
    private var cachedMedName: String? = null

    init {
        loadReference()
    }

    fun refresh() {
        val rxcui = cachedRxcui ?: return
        _uiState.value = DrugReferenceUiState.Loading
        viewModelScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                drugLabelRepository.invalidateAndRefetch(rxcui, cachedMedName)
            }
            _uiState.value = if (fetched != null) {
                fetched.toLoaded(cachedMedName ?: "")
            } else {
                DrugReferenceUiState.NotAvailableOffline(medName = cachedMedName ?: "")
            }
        }
    }

    private fun loadReference() {
        viewModelScope.launch {
            val med = withContext(Dispatchers.IO) { medicationDao.getById(medicationId) }
            if (med == null) {
                _uiState.value = DrugReferenceUiState.NotAvailableOffline("")
                return@launch
            }

            // Use the RxCUI when available; for free-text meds use a synthetic name-based key
            // so openFDA name search still runs and results can be cached.
            val lookupKey = med.rxcui ?: "name:${med.name.trim().lowercase()}"
            cachedRxcui = lookupKey
            cachedMedName = med.name

            val cached = withContext(Dispatchers.IO) {
                drugLabelRepository.getCachedLabel(lookupKey)
            }
            if (cached != null) {
                _uiState.value = cached.toLoaded(med.name)
                return@launch
            }

            _uiState.value = DrugReferenceUiState.Loading

            val fetched = withContext(Dispatchers.IO) {
                drugLabelRepository.getOrFetchLabel(lookupKey, med.name)
            }
            _uiState.value = if (fetched != null) {
                fetched.toLoaded(med.name)
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

    data object Loading : DrugReferenceUiState

    data class Loaded(
        val medName: String,
        val sideEffectsText: String?,
        val interactionsText: String?,
        val warningsText: String?,
        val precautionsText: String?,
        val contraindicationsText: String?,
        val boxedWarningText: String?,
        val source: String,
        val fetchedAtEpochMs: Long,
    ) : DrugReferenceUiState

    data class FreeTextMed(val medName: String) : DrugReferenceUiState

    data class NotAvailableOffline(val medName: String) : DrugReferenceUiState
}

private fun LabelCacheEntity.toLoaded(medName: String) =
    DrugReferenceUiState.Loaded(
        medName = medName,
        sideEffectsText = sideEffectsText,
        interactionsText = interactionsText,
        warningsText = warningsText,
        precautionsText = precautionsText,
        contraindicationsText = contraindicationsText,
        boxedWarningText = boxedWarningText,
        source = source,
        fetchedAtEpochMs = fetchedAtEpochMs,
    )
