package com.beryndil.pharos.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.beryndil.pharos.contacts.ContactRepository
import com.beryndil.pharos.data.regimen.entity.PharmacyEntity
import com.beryndil.pharos.data.regimen.entity.PrescriberEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the Settings screen (theme mode, in-app text scale, default contacts).
 *
 * The ViewModel owns no derived state beyond what the [AppearanceRepository] and
 * [ContactRepository] flows emit; all UI state is a direct projection of persisted preferences.
 */
class SettingsViewModel(
    private val appearanceRepository: AppearanceRepository,
    private val contactRepository: ContactRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        appearanceRepository.observeThemeMode(),
        appearanceRepository.observeTextScale(),
        combine(
            contactRepository.observePrescribers(),
            contactRepository.observePharmacies(),
            contactRepository.observeDefaultPrescriberId(),
            contactRepository.observeDefaultPharmacyId(),
        ) { prescribers, pharmacies, defaultPId, defaultPhId ->
            ContactDefaults(prescribers, pharmacies, defaultPId, defaultPhId)
        },
    ) { themeMode, textScale, contacts ->
        SettingsUiState(
            themeMode = themeMode,
            textScale = textScale,
            prescribers = contacts.prescribers,
            pharmacies = contacts.pharmacies,
            defaultPrescriberId = contacts.defaultPrescriberId,
            defaultPharmacyId = contacts.defaultPharmacyId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun onEvent(event: SettingsEvent) {
        viewModelScope.launch {
            when (event) {
                is SettingsEvent.SetThemeMode -> appearanceRepository.setThemeMode(event.mode)
                is SettingsEvent.SetTextScale -> appearanceRepository.setTextScale(event.scale)
                is SettingsEvent.SetDefaultPrescriber ->
                    contactRepository.setDefaultPrescriberId(event.id)
                is SettingsEvent.SetDefaultPharmacy ->
                    contactRepository.setDefaultPharmacyId(event.id)
            }
        }
    }

    companion object {
        fun factory(
            appearanceRepository: AppearanceRepository,
            contactRepository: ContactRepository,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(appearanceRepository, contactRepository) as T
            }
    }
}

private data class ContactDefaults(
    val prescribers: List<PrescriberEntity>,
    val pharmacies: List<PharmacyEntity>,
    val defaultPrescriberId: String?,
    val defaultPharmacyId: String?,
)

/** Immutable snapshot of the settings screen state. */
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val textScale: TextScale = TextScale.DEFAULT,
    val prescribers: List<PrescriberEntity> = emptyList(),
    val pharmacies: List<PharmacyEntity> = emptyList(),
    val defaultPrescriberId: String? = null,
    val defaultPharmacyId: String? = null,
)

/** All events the settings screen can raise. */
sealed interface SettingsEvent {
    data class SetThemeMode(val mode: ThemeMode) : SettingsEvent
    data class SetTextScale(val scale: TextScale) : SettingsEvent
    data class SetDefaultPrescriber(val id: String?) : SettingsEvent
    data class SetDefaultPharmacy(val id: String?) : SettingsEvent
}
