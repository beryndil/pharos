package com.beryndil.pharos.contacts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.data.regimen.entity.PharmacyEntity
import com.beryndil.pharos.data.regimen.entity.PrescriberEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Edit / delete / add state for a contact dialog.
 */
@Immutable
sealed interface ContactEditDialog {
    /** No dialog open. */
    data object None : ContactEditDialog

    /** Adding a new prescriber. */
    data class AddPrescriber(
        val currentName: String = "",
        val currentPhone: String = "",
        val currentPractice: String = "",
    ) : ContactEditDialog

    /** Adding a new pharmacy. */
    data class AddPharmacy(
        val currentName: String = "",
        val currentPhone: String = "",
    ) : ContactEditDialog

    /** Editing an existing prescriber. */
    data class EditPrescriber(
        val prescriber: PrescriberEntity,
        val currentName: String,
        val currentPhone: String,
        val currentPractice: String,
    ) : ContactEditDialog

    /** Editing an existing pharmacy. */
    data class EditPharmacy(
        val pharmacy: PharmacyEntity,
        val currentName: String,
        val currentPhone: String,
    ) : ContactEditDialog

    /** Confirming deletion. */
    data class ConfirmDelete(val contactId: String, val isPharmacy: Boolean) : ContactEditDialog
}

@Immutable
data class SavedContactsUiState(
    val prescribers: List<PrescriberEntity> = emptyList(),
    val pharmacies: List<PharmacyEntity> = emptyList(),
    val defaultPrescriberId: String? = null,
    val defaultPharmacyId: String? = null,
    val dialog: ContactEditDialog = ContactEditDialog.None,
)

sealed interface ContactsEvent {
    data object AddPrescriberRequested : ContactsEvent
    data object AddPharmacyRequested : ContactsEvent
    data class EditPrescriberRequested(val prescriber: PrescriberEntity) : ContactsEvent
    data class EditPharmacyRequested(val pharmacy: PharmacyEntity) : ContactsEvent
    data class EditNameChanged(val value: String) : ContactsEvent
    data class EditPhoneChanged(val value: String) : ContactsEvent
    data class EditPracticeChanged(val value: String) : ContactsEvent
    /** Confirms both add-new and save-edit dialogs. */
    data object SaveConfirmed : ContactsEvent
    data class DeleteRequested(val id: String, val isPharmacy: Boolean) : ContactsEvent
    data object DeleteConfirmed : ContactsEvent
    data object DialogDismissed : ContactsEvent
    /** Toggle the default prescriber: sets this id if not already default, clears if already default. */
    data class SetDefaultPrescriber(val id: String) : ContactsEvent
    /** Toggle the default pharmacy: sets this id if not already default, clears if already default. */
    data class SetDefaultPharmacy(val id: String) : ContactsEvent
}

/**
 * ViewModel for the Saved Contacts manage screen (spec V1.3-F1).
 *
 * Deleting a saved contact does NOT alter medications that already reference it — medications
 * retain their stored name/phone strings.
 */
class SavedContactsViewModel(
    private val repository: ContactRepository,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SavedContactsUiState())
    val uiState: StateFlow<SavedContactsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observePrescribers(),
                repository.observePharmacies(),
                repository.observeDefaultPrescriberId(),
                repository.observeDefaultPharmacyId(),
            ) { prescribers, pharmacies, defaultPId, defaultPhId ->
                _uiState.update {
                    it.copy(
                        prescribers = prescribers,
                        pharmacies = pharmacies,
                        defaultPrescriberId = defaultPId,
                        defaultPharmacyId = defaultPhId,
                    )
                }
            }.collect {}
        }
    }

    fun onEvent(event: ContactsEvent) {
        when (event) {
            is ContactsEvent.AddPrescriberRequested ->
                _uiState.update { it.copy(dialog = ContactEditDialog.AddPrescriber()) }
            is ContactsEvent.AddPharmacyRequested ->
                _uiState.update { it.copy(dialog = ContactEditDialog.AddPharmacy()) }
            is ContactsEvent.EditPrescriberRequested ->
                _uiState.update {
                    it.copy(
                        dialog = ContactEditDialog.EditPrescriber(
                            prescriber = event.prescriber,
                            currentName = event.prescriber.name,
                            currentPhone = event.prescriber.phone ?: "",
                            currentPractice = event.prescriber.practice ?: "",
                        ),
                    )
                }
            is ContactsEvent.EditPharmacyRequested ->
                _uiState.update {
                    it.copy(
                        dialog = ContactEditDialog.EditPharmacy(
                            pharmacy = event.pharmacy,
                            currentName = event.pharmacy.name,
                            currentPhone = event.pharmacy.phone ?: "",
                        ),
                    )
                }
            is ContactsEvent.EditNameChanged ->
                _uiState.update { state ->
                    state.copy(
                        dialog = when (val d = state.dialog) {
                            is ContactEditDialog.AddPrescriber  -> d.copy(currentName = event.value)
                            is ContactEditDialog.AddPharmacy    -> d.copy(currentName = event.value)
                            is ContactEditDialog.EditPrescriber -> d.copy(currentName = event.value)
                            is ContactEditDialog.EditPharmacy   -> d.copy(currentName = event.value)
                            else -> d
                        },
                    )
                }
            is ContactsEvent.EditPhoneChanged ->
                _uiState.update { state ->
                    state.copy(
                        dialog = when (val d = state.dialog) {
                            is ContactEditDialog.AddPrescriber  -> d.copy(currentPhone = event.value)
                            is ContactEditDialog.AddPharmacy    -> d.copy(currentPhone = event.value)
                            is ContactEditDialog.EditPrescriber -> d.copy(currentPhone = event.value)
                            is ContactEditDialog.EditPharmacy   -> d.copy(currentPhone = event.value)
                            else -> d
                        },
                    )
                }
            is ContactsEvent.EditPracticeChanged ->
                _uiState.update { state ->
                    state.copy(
                        dialog = when (val d = state.dialog) {
                            is ContactEditDialog.AddPrescriber  -> d.copy(currentPractice = event.value)
                            is ContactEditDialog.EditPrescriber -> d.copy(currentPractice = event.value)
                            else -> d
                        },
                    )
                }
            is ContactsEvent.SaveConfirmed -> onSaveConfirmed()
            is ContactsEvent.DeleteRequested ->
                _uiState.update {
                    it.copy(dialog = ContactEditDialog.ConfirmDelete(event.id, event.isPharmacy))
                }
            is ContactsEvent.DeleteConfirmed -> onDeleteConfirmed()
            is ContactsEvent.DialogDismissed ->
                _uiState.update { it.copy(dialog = ContactEditDialog.None) }
            is ContactsEvent.SetDefaultPrescriber -> viewModelScope.launch {
                val current = _uiState.value.defaultPrescriberId
                withContext(ioDispatcher) {
                    repository.setDefaultPrescriberId(if (event.id == current) null else event.id)
                }
            }
            is ContactsEvent.SetDefaultPharmacy -> viewModelScope.launch {
                val current = _uiState.value.defaultPharmacyId
                withContext(ioDispatcher) {
                    repository.setDefaultPharmacyId(if (event.id == current) null else event.id)
                }
            }
        }
    }

    private fun onSaveConfirmed() {
        val dialog = _uiState.value.dialog
        viewModelScope.launch {
            withContext(ioDispatcher) {
                when (dialog) {
                    is ContactEditDialog.AddPrescriber -> {
                        val name = dialog.currentName.trim()
                        if (name.isNotEmpty()) {
                            repository.rememberPrescriber(
                                name,
                                dialog.currentPhone.trim().ifEmpty { null },
                                dialog.currentPractice.trim().ifEmpty { null },
                            )
                        }
                    }
                    is ContactEditDialog.AddPharmacy -> {
                        val name = dialog.currentName.trim()
                        if (name.isNotEmpty()) {
                            repository.rememberPharmacy(name, dialog.currentPhone.trim().ifEmpty { null })
                        }
                    }
                    is ContactEditDialog.EditPrescriber ->
                        repository.updatePrescriber(
                            dialog.prescriber.copy(
                                name     = dialog.currentName.trim().ifEmpty { dialog.prescriber.name },
                                phone    = dialog.currentPhone.trim().ifEmpty { null },
                                practice = dialog.currentPractice.trim().ifEmpty { null },
                            ),
                        )
                    is ContactEditDialog.EditPharmacy ->
                        repository.updatePharmacy(
                            dialog.pharmacy.copy(
                                name  = dialog.currentName.trim().ifEmpty { dialog.pharmacy.name },
                                phone = dialog.currentPhone.trim().ifEmpty { null },
                            ),
                        )
                    else -> Unit
                }
            }
            _uiState.update { it.copy(dialog = ContactEditDialog.None) }
        }
    }

    private fun onDeleteConfirmed() {
        val dialog = _uiState.value.dialog as? ContactEditDialog.ConfirmDelete ?: return
        viewModelScope.launch {
            withContext(ioDispatcher) {
                if (dialog.isPharmacy) {
                    repository.deletePharmacy(dialog.contactId)
                    // Clear default if the deleted contact was the default.
                    if (_uiState.value.defaultPharmacyId == dialog.contactId) {
                        repository.setDefaultPharmacyId(null)
                    }
                } else {
                    repository.deletePrescriber(dialog.contactId)
                    if (_uiState.value.defaultPrescriberId == dialog.contactId) {
                        repository.setDefaultPrescriberId(null)
                    }
                }
            }
            _uiState.update { it.copy(dialog = ContactEditDialog.None) }
        }
    }

    companion object {
        fun factory(repository: ContactRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { SavedContactsViewModel(repository = repository) }
            }
    }
}
