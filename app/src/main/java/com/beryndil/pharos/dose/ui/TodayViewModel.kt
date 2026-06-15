package com.beryndil.pharos.dose.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.data.dose.DoseRepository
import com.beryndil.pharos.data.dose.DoseRow
import com.beryndil.pharos.data.dose.PrnMedRow
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.medication.export.MedListPdfExporter
import com.beryndil.pharos.settings.UserProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Maximum number of upcoming doses shown in the "Next up" summary header. */
const val MAX_NEXT_UP = 5

/**
 * A single row in the "Next up" summary (F3 — top-of-Today upcoming doses).
 *
 * Only SCHEDULED doses are included — DUE/SNOOZED are already prominently visible
 * in the main action rows and do not need duplication in the summary.
 */
@Immutable
data class NextUpItem(
    val doseId: String,
    val medName: String,
    val strength: String,
    val doseAmount: String,
    val dueEpochMs: Long,
)

@Immutable
data class TodayUiState(
    val doses: List<DoseRow> = emptyList(),
    val prnMeds: List<PrnMedRow> = emptyList(),
    /**
     * Non-null when a PRN log triggered the daily-max advisory (spec §2.7, Law 3).
     * Holds (doseNumber, dailyMax). The UI formats the user-facing string from these values.
     * Advisory only — never blocks or forbids the dose (already logged before this is set).
     */
    val prnWarningDoseNumber: Int? = null,
    val prnWarningDailyMax: Int = 0,
    /**
     * Upcoming SCHEDULED doses for the "Next up" summary header (F3).
     * Derived from [doses]; at most [MAX_NEXT_UP] items, sorted by due time.
     */
    val nextUp: List<NextUpItem> = emptyList(),
    /** True while the "Email meds list" confirm dialog should be shown (Law 4 guard). */
    val showEmailConfirmDialog: Boolean = false,
    /**
     * Set to the generated cache PDF file after the user confirms the email action (F4).
     * The UI observes this: when non-null it builds the FileProvider URI + Intent, launches
     * the chooser, then fires [TodayEvent.EmailMedListIntentConsumed] to clear this field.
     */
    val pendingEmailFile: File? = null,
    /** Non-null when PDF generation fails; cleared by [TodayEvent.EmailMedListErrorDismissed]. */
    val emailError: String? = null,
)

sealed interface TodayEvent {
    data class Take(val doseId: String) : TodayEvent
    data class Snooze(val doseId: String) : TodayEvent
    data class Skip(val doseId: String) : TodayEvent

    /**
     * User tapped "Log dose" for a PRN medication (spec §2.7).
     * The dose is logged immediately; [DoseRepository.logPrn] returns a [PrnLogResult]
     * whose [exceedsMax] field drives the optional daily-max advisory.
     */
    data class LogPrn(val medicationId: String, val scheduleId: String) : TodayEvent

    /** Dismiss the PRN daily-max advisory dialog (advisory only, Law 3). */
    data object DismissPrnWarning : TodayEvent

    // ── F4 — Email meds-list PDF ──────────────────────────────────────────

    /** User tapped "Email meds list" → show Law-4 confirm dialog. */
    data object EmailMedListRequest : TodayEvent

    /**
     * User confirmed in the Law-4 dialog → generate PDF to cache file.
     * On success: sets [TodayUiState.pendingEmailFile] so the UI can launch the chooser.
     * On failure: sets [TodayUiState.emailError].
     */
    data object EmailMedListConfirm : TodayEvent

    /** User dismissed the confirm dialog without sending. */
    data object EmailMedListDismiss : TodayEvent

    /** UI has launched the share chooser and consumed [TodayUiState.pendingEmailFile]. */
    data object EmailMedListIntentConsumed : TodayEvent

    /** User dismissed the error snackbar/dialog. */
    data object EmailMedListErrorDismissed : TodayEvent
}

/** Drives the today/upcoming dose surface (Slice 5). Actions route to the dose state machine. */
class TodayViewModel(
    private val doseRepository: DoseRepository,
    private val pdfExporter: MedListPdfExporter,
    /** Application cache directory for writing the email-PDF temp file. */
    private val cacheDir: File,
    private val userProfileRepository: UserProfileRepository,
) : ViewModel() {

    /** Non-null while the PRN daily-max advisory should be shown. */
    private val _prnWarning = MutableStateFlow<Pair<Int, Int>?>(null)

    /** Holds all transient email-action state in a single flow so we stay within combine's 5-arg limit. */
    private data class EmailState(
        val showConfirm: Boolean = false,
        val pendingFile: File? = null,
        val error: String? = null,
    )
    private val _emailState = MutableStateFlow(EmailState())

    val uiState: StateFlow<TodayUiState> = combine(
        doseRepository.observeTodayDoses(),
        doseRepository.observePrnMeds(),
        _prnWarning,
        _emailState,
    ) { doses, prnMeds, warning, email ->
        TodayUiState(
            doses = doses,
            prnMeds = prnMeds,
            prnWarningDoseNumber = warning?.first,
            prnWarningDailyMax = warning?.second ?: 0,
            nextUp = selectNextUp(doses),
            showEmailConfirmDialog = email.showConfirm,
            pendingEmailFile = email.pendingFile,
            emailError = email.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TodayUiState(),
    )

    fun onEvent(event: TodayEvent) {
        when (event) {
            is TodayEvent.DismissPrnWarning         -> { _prnWarning.value = null; return }
            is TodayEvent.EmailMedListRequest       -> { _emailState.value = _emailState.value.copy(showConfirm = true); return }
            is TodayEvent.EmailMedListDismiss       -> { _emailState.value = _emailState.value.copy(showConfirm = false); return }
            is TodayEvent.EmailMedListIntentConsumed-> { _emailState.value = _emailState.value.copy(pendingFile = null); return }
            is TodayEvent.EmailMedListErrorDismissed-> { _emailState.value = _emailState.value.copy(error = null); return }
            is TodayEvent.EmailMedListConfirm       -> { _emailState.value = _emailState.value.copy(showConfirm = false); generateEmailPdf(); return }
            else -> { /* handled in the IO coroutine below */ }
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                when (event) {
                    is TodayEvent.Take -> doseRepository.take(event.doseId)
                    is TodayEvent.Snooze -> doseRepository.snooze(event.doseId)
                    is TodayEvent.Skip -> doseRepository.skip(event.doseId)
                    is TodayEvent.LogPrn -> {
                        val result = doseRepository.logPrn(event.medicationId, event.scheduleId)
                        if (result.exceedsMax && result.dailyMax != null) {
                            _prnWarning.value = Pair(result.doseNumber, result.dailyMax)
                        }
                    }
                    // All other branches handled synchronously above.
                    else -> Unit
                }
            }
        }
    }

    private fun generateEmailPdf() {
        viewModelScope.launch {
            try {
                val profile = userProfileRepository.getProfile()
                val exportDir = File(cacheDir, "exports")
                exportDir.mkdirs()
                val pdfFile = File(exportDir, "medication-list.pdf")
                pdfFile.outputStream().use { stream ->
                    pdfExporter.writeTo(stream, userProfile = profile)
                }
                _emailState.value = _emailState.value.copy(pendingFile = pdfFile)
            } catch (e: Exception) {
                _emailState.value = _emailState.value.copy(error = "Could not generate the PDF: ${e.message}")
            }
        }
    }

    // ── Test accessors ────────────────────────────────────────────────────

    /**
     * Direct access to the email action state for unit tests.
     *
     * [uiState] is driven by [combine] with [SharingStarted.WhileSubscribed], so
     * [uiState.value] returns the `initialValue` until there is an active subscriber AND
     * all upstream flows (including Room Flows) have emitted. Tests that want to verify
     * the dialog-gating state machine can use these properties to avoid the Room emission
     * dependency entirely.
     */
    internal val emailConfirmVisible: Boolean get() = _emailState.value.showConfirm
    internal val pendingEmailFileRaw: java.io.File? get() = _emailState.value.pendingFile

    companion object {

        fun factory(
            doseRepository: DoseRepository,
            pdfExporter: MedListPdfExporter,
            cacheDir: File,
            userProfileRepository: UserProfileRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    TodayViewModel(
                        doseRepository = doseRepository,
                        pdfExporter = pdfExporter,
                        cacheDir = cacheDir,
                        userProfileRepository = userProfileRepository,
                    )
                }
            }
    }
}

/**
 * Selects the next [max] upcoming SCHEDULED doses from [doses], sorted by due time.
 *
 * Internal visibility for unit-testing without coroutines.
 * DUE and SNOOZED doses are already shown prominently in the action rows and are excluded
 * from the summary to avoid redundancy.
 */
internal fun selectNextUp(doses: List<DoseRow>, max: Int = MAX_NEXT_UP, nowMs: Long = System.currentTimeMillis()): List<NextUpItem> =
    doses
        .filter { it.state == DoseState.SCHEDULED && it.dueEpochMs > nowMs }
        .sortedBy { it.dueEpochMs }
        .take(max)
        .map { NextUpItem(doseId = it.doseId, medName = it.medName, strength = it.strength, doseAmount = it.doseAmount, dueEpochMs = it.dueEpochMs) }
