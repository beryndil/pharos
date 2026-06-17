package com.beryndil.pharos.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class UserProfileUiState(
    val name: String = "",
    val dateOfBirth: String = "",       // ISO 8601 storage (YYYY-MM-DD)
    val dateOfBirthDate: LocalDate? = null, // derived view for the date picker
    val phone: String = "",             // digits only
    val address: String = "",
    val allergies: String = "",
    val insuranceProvider: String = "",
    val insuranceMemberId: String = "",
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",  // digits only
    val saved: Boolean = false,
)

sealed interface UserProfileEvent {
    data class NameChanged(val value: String) : UserProfileEvent
    data class DobDateChanged(val date: LocalDate?) : UserProfileEvent
    data class PhoneChanged(val value: String) : UserProfileEvent
    data class AddressChanged(val value: String) : UserProfileEvent
    data class AllergiesChanged(val value: String) : UserProfileEvent
    data class InsuranceProviderChanged(val value: String) : UserProfileEvent
    data class InsuranceMemberIdChanged(val value: String) : UserProfileEvent
    data class EmergencyContactNameChanged(val value: String) : UserProfileEvent
    data class EmergencyContactPhoneChanged(val value: String) : UserProfileEvent
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
            val storedDob = profile.dateOfBirth
            val dobDate = storedDob?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            _uiState.update {
                it.copy(
                    name = profile.name ?: "",
                    dateOfBirth = dobDate?.toString() ?: "",
                    dateOfBirthDate = dobDate,
                    phone = (profile.phone ?: "").filter { c -> c.isDigit() }.take(10),
                    address = profile.address ?: "",
                    allergies = profile.allergies ?: "",
                    insuranceProvider = profile.insuranceProvider ?: "",
                    insuranceMemberId = profile.insuranceMemberId ?: "",
                    emergencyContactName = profile.emergencyContactName ?: "",
                    emergencyContactPhone = (profile.emergencyContactPhone ?: "").filter { c -> c.isDigit() }.take(10),
                )
            }
        }
    }

    fun onEvent(event: UserProfileEvent) {
        when (event) {
            is UserProfileEvent.NameChanged -> _uiState.update { it.copy(name = event.value) }
            is UserProfileEvent.DobDateChanged -> _uiState.update {
                it.copy(dateOfBirth = event.date?.toString() ?: "", dateOfBirthDate = event.date)
            }
            is UserProfileEvent.PhoneChanged -> _uiState.update { it.copy(phone = event.value) }
            is UserProfileEvent.AddressChanged -> _uiState.update { it.copy(address = event.value) }
            is UserProfileEvent.AllergiesChanged -> _uiState.update { it.copy(allergies = event.value) }
            is UserProfileEvent.InsuranceProviderChanged -> _uiState.update { it.copy(insuranceProvider = event.value) }
            is UserProfileEvent.InsuranceMemberIdChanged -> _uiState.update { it.copy(insuranceMemberId = event.value) }
            is UserProfileEvent.EmergencyContactNameChanged -> _uiState.update { it.copy(emergencyContactName = event.value) }
            is UserProfileEvent.EmergencyContactPhoneChanged -> _uiState.update { it.copy(emergencyContactPhone = event.value) }
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
                    insuranceProvider = state.insuranceProvider.trim().ifEmpty { null },
                    insuranceMemberId = state.insuranceMemberId.trim().ifEmpty { null },
                    emergencyContactName = state.emergencyContactName.trim().ifEmpty { null },
                    emergencyContactPhone = state.emergencyContactPhone.trim().ifEmpty { null },
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
