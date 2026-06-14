package com.beryndil.pharos.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UserProfileUiState(
    val name: String = "",
    val dateOfBirth: String = "",
    val phone: String = "",
    val address: String = "",
    val allergies: String = "",
    val saved: Boolean = false,
)

sealed interface UserProfileEvent {
    data class NameChanged(val value: String) : UserProfileEvent
    data class DobChanged(val value: String) : UserProfileEvent
    data class PhoneChanged(val value: String) : UserProfileEvent
    data class AddressChanged(val value: String) : UserProfileEvent
    data class AllergiesChanged(val value: String) : UserProfileEvent
    data object Save : UserProfileEvent
    data object DismissSaved : UserProfileEvent
}

class UserProfileViewModel(
    private val repository: UserProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserProfileUiState())
    val uiState: StateFlow<UserProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = repository.getProfile()
            _uiState.update {
                it.copy(
                    name = profile.name ?: "",
                    dateOfBirth = profile.dateOfBirth ?: "",
                    phone = profile.phone ?: "",
                    address = profile.address ?: "",
                    allergies = profile.allergies ?: "",
                )
            }
        }
    }

    fun onEvent(event: UserProfileEvent) {
        when (event) {
            is UserProfileEvent.NameChanged -> _uiState.update { it.copy(name = event.value) }
            is UserProfileEvent.DobChanged -> _uiState.update { it.copy(dateOfBirth = event.value) }
            is UserProfileEvent.PhoneChanged -> _uiState.update { it.copy(phone = event.value) }
            is UserProfileEvent.AddressChanged -> _uiState.update { it.copy(address = event.value) }
            is UserProfileEvent.AllergiesChanged -> _uiState.update { it.copy(allergies = event.value) }
            is UserProfileEvent.Save -> save()
            is UserProfileEvent.DismissSaved -> _uiState.update { it.copy(saved = false) }
        }
    }

    private fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            repository.saveProfile(
                UserProfile(
                    name = state.name.trim().ifEmpty { null },
                    dateOfBirth = state.dateOfBirth.trim().ifEmpty { null },
                    phone = state.phone.trim().ifEmpty { null },
                    address = state.address.trim().ifEmpty { null },
                    allergies = state.allergies.trim().ifEmpty { null },
                ),
            )
            _uiState.update { it.copy(saved = true) }
        }
    }

    companion object {
        fun factory(repository: UserProfileRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    UserProfileViewModel(repository) as T
            }
    }
}
