package com.beryndil.pharos.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the Settings screen (theme mode + in-app text scale).
 *
 * The ViewModel owns no derived state beyond what the two [AppearanceRepository] flows emit;
 * all UI state is a direct projection of the persisted preferences. This means the settings
 * screen is always in sync with [com.beryndil.pharos.MainActivity]'s live theme collection —
 * they read from the same DB rows via independent Flow subscriptions.
 */
class SettingsViewModel(
    private val appearanceRepository: AppearanceRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        appearanceRepository.observeThemeMode(),
        appearanceRepository.observeTextScale(),
    ) { themeMode, textScale ->
        SettingsUiState(themeMode = themeMode, textScale = textScale)
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
            }
        }
    }

    companion object {
        fun factory(appearanceRepository: AppearanceRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(appearanceRepository) as T
            }
    }
}

/** Immutable snapshot of the settings screen state. */
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val textScale: TextScale = TextScale.DEFAULT,
)

/** All events the settings screen can raise. */
sealed interface SettingsEvent {
    data class SetThemeMode(val mode: ThemeMode) : SettingsEvent
    data class SetTextScale(val scale: TextScale) : SettingsEvent
}
