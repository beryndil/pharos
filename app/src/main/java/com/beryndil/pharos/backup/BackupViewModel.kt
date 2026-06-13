package com.beryndil.pharos.backup

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.beryndil.pharos.medication.export.PdfExportOptions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class BackupUiState(
    val operation: BackupOperation = BackupOperation.Idle,
    /** SAF intent to fire (set while waiting for the launcher to return a URI). */
    val pendingAction: PendingAction? = null,
    /**
     * True when an auto-backup file is present in Downloads AND the regimen DB is empty
     * (fresh install or post-uninstall). The UI shows a restore prompt when this is set.
     */
    val autoRestoreAvailable: Boolean = false,
)

sealed interface BackupOperation {
    data object Idle : BackupOperation
    data object InProgress : BackupOperation
    data class BackupSuccess(val uri: Uri) : BackupOperation
    data class RestoreSuccess(val medicationCount: Int) : BackupOperation
    data object ExportSuccess : BackupOperation
    data class Error(val message: String) : BackupOperation
}

/**
 * Which SAF document picker the user opened — tracked so the viewmodel knows what to do
 * with the URI the user picks.
 */
enum class PendingAction {
    CREATE_BACKUP,
    OPEN_BACKUP,
    EXPORT_PDF,
    EXPORT_CSV,
}

sealed interface BackupEvent {
    /** User typed a passphrase and confirmed — create backup to [uri]. */
    data class CreateBackup(val passphrase: CharArray, val uri: Uri) : BackupEvent

    /** User typed a passphrase and confirmed — restore from [uri]. */
    data class Restore(val passphrase: CharArray, val uri: Uri) : BackupEvent

    /** Export a PDF medication list to [uri] with [options] controlling which fields appear. */
    data class ExportPdf(val uri: Uri, val options: PdfExportOptions = PdfExportOptions()) : BackupEvent

    /** Export a CSV medication list to [uri]. */
    data class ExportCsv(val uri: Uri) : BackupEvent

    /** Clear any result/error toast so the screen returns to Idle. */
    data object DismissResult : BackupEvent

    /** User confirmed the auto-restore prompt — restore from Downloads auto-backup. */
    data object RestoreFromAutoBackup : BackupEvent

    /** User dismissed the auto-restore prompt without restoring. */
    data object DismissAutoRestorePrompt : BackupEvent
}

/**
 * ViewModel for the Backup & Restore screen (spec §2.12).
 *
 * All crypto operations run on [kotlinx.coroutines.Dispatchers.IO] via [BackupRepository].
 * Passphrase [CharArray]s are not stored in the state; they are forwarded to [BackupRepository]
 * which zeros them after key derivation (Standards §6).
 *
 * On creation, checks whether an auto-backup file exists in Downloads AND the regimen DB is
 * empty (e.g., fresh install after an uninstall). When both are true, [BackupUiState.autoRestoreAvailable]
 * is set so the screen can prompt the user to restore with one tap.
 */
class BackupViewModel(
    private val repository: BackupRepository,
    private val autoBackupManager: AutoBackupManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val isEmpty = repository.isRegimenEmpty()
            val hasBackup = autoBackupManager.hasAutoBackupFile()
            if (isEmpty && hasBackup) {
                _uiState.update { it.copy(autoRestoreAvailable = true) }
            }
        }
    }

    fun onEvent(event: BackupEvent) {
        when (event) {
            is BackupEvent.CreateBackup -> createBackup(event.passphrase, event.uri)
            is BackupEvent.Restore -> restore(event.passphrase, event.uri)
            is BackupEvent.ExportPdf -> exportPdf(event.uri, event.options)
            is BackupEvent.ExportCsv -> exportCsv(event.uri)
            is BackupEvent.DismissResult -> _uiState.update { it.copy(operation = BackupOperation.Idle) }
            is BackupEvent.RestoreFromAutoBackup -> restoreFromAutoBackup()
            is BackupEvent.DismissAutoRestorePrompt ->
                _uiState.update { it.copy(autoRestoreAvailable = false) }
        }
    }

    private fun createBackup(passphrase: CharArray, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(operation = BackupOperation.InProgress) }
            val result = repository.createBackup(passphrase, uri)
            _uiState.update {
                it.copy(
                    operation = when (result) {
                        is BackupResult.Success -> BackupOperation.BackupSuccess(uri)
                        is BackupResult.Error -> BackupOperation.Error(result.message)
                    },
                )
            }
        }
    }

    private fun restore(passphrase: CharArray, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(operation = BackupOperation.InProgress) }
            val result = repository.restore(passphrase, uri)
            _uiState.update {
                it.copy(
                    operation = when (result) {
                        is RestoreResult.Success -> BackupOperation.RestoreSuccess(result.medicationCount)
                        is RestoreResult.Error -> BackupOperation.Error(result.message)
                    },
                )
            }
        }
    }

    private fun exportPdf(uri: Uri, options: PdfExportOptions) {
        viewModelScope.launch {
            _uiState.update { it.copy(operation = BackupOperation.InProgress) }
            val result = repository.exportPdf(uri, options)
            _uiState.update {
                it.copy(
                    operation = when (result) {
                        is ExportResult.Success -> BackupOperation.ExportSuccess
                        is ExportResult.Error -> BackupOperation.Error(result.message)
                    },
                )
            }
        }
    }

    private fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(operation = BackupOperation.InProgress) }
            val result = repository.exportCsv(uri)
            _uiState.update {
                it.copy(
                    operation = when (result) {
                        is ExportResult.Success -> BackupOperation.ExportSuccess
                        is ExportResult.Error -> BackupOperation.Error(result.message)
                    },
                )
            }
        }
    }

    private fun restoreFromAutoBackup() {
        viewModelScope.launch {
            _uiState.update { it.copy(operation = BackupOperation.InProgress, autoRestoreAvailable = false) }
            val result = autoBackupManager.restoreAutoBackup()
            _uiState.update {
                it.copy(
                    operation = when (result) {
                        is RestoreResult.Success -> BackupOperation.RestoreSuccess(result.medicationCount)
                        is RestoreResult.Error -> BackupOperation.Error(result.message)
                    },
                )
            }
        }
    }

    companion object {
        fun factory(
            repository: BackupRepository,
            autoBackupManager: AutoBackupManager,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    BackupViewModel(
                        repository = repository,
                        autoBackupManager = autoBackupManager,
                    )
                }
            }
    }
}
