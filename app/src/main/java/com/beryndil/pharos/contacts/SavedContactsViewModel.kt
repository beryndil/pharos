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
 * Edit / delete state for a contact that the user has tapped.
 */
@Immutable
sealed interface ContactEditDialog {
    /** No dialog open. */
    data object None : ContactEditDialog

    /** Editing a prescriber. [current] holds the live text in the dialog. */
    data class EditPrescriber(
        val prescriber: PrescriberEntity,
        val currentName: String,
        val currentPhone: String,
    ) : ContactEditDialog

    /** Editing a pharmacy. */
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
    val dialog: ContactEditDialog = ContactEditDialog.None,
)

sealed interface ContactsEvent {
    data class EditPrescriberRequested(val prescriber: PrescriberEntity) : ContactsEvent
    data class EditPharmacyRequested(val pharmacy: PharmacyEntity) : ContactsEvent
    data class EditNameChanged(val value: String) : ContactsEvent
    data class EditPhoneChanged(val value: String) : ContactsEvent
    data object SaveEditConfirmed : ContactsEvent
    data class DeleteRequested(val id: String, val isPharmacy: Boolean) : ContactsEvent
    data object DeleteConfirmed : ContactsEvent
    data object DialogDismissed : ContactsEvent
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
            ) { p, ph -> Pair(p, ph) }.collect { (prescribers, pharmacies) ->
                _uiState.update { it.copy(prescribers = prescribers, pharmacies = pharmacies) }
            }
        }
    }

    fun onEvent(event: ContactsEvent) {
        when (event) {
            is ContactsEvent.EditPrescriberRequested ->
                _uiState.update {
                    it.copy(
                        dialog = ContactEditDialog.EditPrescriber(
                            prescriber = event.prescriber,
                            currentName = event.prescriber.name,
                            currentPhone = event.prescriber.phone ?: "",
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
                            is ContactEditDialog.EditPrescriber -> d.copy(currentName = event.value)
                            is ContactEditDialog.EditPharmacy -> d.copy(currentName = event.value)
                            else -> d
                        },
                    )
                }
            is ContactsEvent.EditPhoneChanged ->
                _uiState.update { state ->
                    state.copy(
                        dialog = when (val d = state.dialog) {
                            is ContactEditDialog.EditPrescriber -> d.copy(currentPhone = event.value)
                            is ContactEditDialog.EditPharmacy -> d.copy(currentPhone = event.value)
                            else -> d
                        },
                    )
                }
            is ContactsEvent.SaveEditConfirmed -> onSaveEdit()
            is ContactsEvent.DeleteRequested ->
                _uiState.update {
                    it.copy(dialog = ContactEditDialog.ConfirmDelete(event.id, event.isPharmacy))
                }
            is ContactsEvent.DeleteConfirmed -> onDeleteConfirmed()
            is ContactsEvent.DialogDismissed ->
                _uiState.update { it.copy(dialog = ContactEditDialog.None) }
        }
    }

    private fun onSaveEdit() {
        val dialog = _uiState.value.dialog
        viewModelScope.launch {
            withContext(ioDispatcher) {
                when (dialog) {
                    is ContactEditDialog.EditPrescriber ->
                        repository.updatePrescriber(
                            dialog.prescriber.copy(
                                name = dialog.currentName.trim().ifEmpty { dialog.prescriber.name },
                                phone = dialog.currentPhone.trim().ifEmpty { null },
                            ),
                        )
                    is ContactEditDialog.EditPharmacy ->
                        repository.updatePharmacy(
                            dialog.pharmacy.copy(
                                name = dialog.currentName.trim().ifEmpty { dialog.pharmacy.name },
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
                if (dialog.isPharmacy) repository.deletePharmacy(dialog.contactId)
                else repository.deletePrescriber(dialog.contactId)
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
