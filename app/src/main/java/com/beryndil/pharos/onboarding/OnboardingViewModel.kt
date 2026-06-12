package com.beryndil.pharos.onboarding

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beryndil.pharos.alarm.AlarmCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

// ── Step enum ────────────────────────────────────────────────────────────────────────────────

/**
 * The ordered steps of the first-launch onboarding flow (spec §2.14).
 *
 * [NOTIFICATION_PERMISSION] is only inserted on API 33+ (runtime POST_NOTIFICATIONS).
 * [AUTO_START] is only inserted for Xiaomi, OPPO, vivo, and Honor (D5 — OEM killer detection).
 * The ordering in this enum is informational; the actual sequence is built by [buildSteps].
 */
enum class OnboardingStep {
    WELCOME,
    NOTIFICATION_PERMISSION,
    EXACT_ALARM_PERMISSION,
    BATTERY_OPTIMIZATION,
    AUTO_START,
    TEST_REMINDER,
}

// ── UI state ─────────────────────────────────────────────────────────────────────────────────

/**
 * Immutable UI state emitted by [OnboardingViewModel].
 *
 * [currentStep] drives which step content the composable renders.
 * [totalSteps] and [currentStepIndex] (0-based) power the "Step N of M" progress indicator.
 * [oemName] is forwarded to the auto-start step so the composable can show per-OEM instructions.
 * [testReminderSent] flips to true after the test alarm is scheduled — composable shows confirmation.
 * [isComplete] flips to true after [OnboardingEvent.CompleteOnboarding] persists the flag — the
 * composable observes this to navigate away via its [onDone] callback.
 */
data class OnboardingUiState(
    val currentStep: OnboardingStep = OnboardingStep.WELCOME,
    val totalSteps: Int = 0,
    val currentStepIndex: Int = 0,
    val oemName: String = "",
    val testReminderSent: Boolean = false,
    val isComplete: Boolean = false,
)

// ── Events ───────────────────────────────────────────────────────────────────────────────────

sealed class OnboardingEvent {
    /** Advance to the next step. */
    object NextStep : OnboardingEvent()

    /** Return to the previous step. No-op on the first step. */
    object PreviousStep : OnboardingEvent()

    /** Schedule the test alarm (Law 6). Best-effort: failure marks [OnboardingUiState.testReminderSent] true anyway. */
    object SendTestReminder : OnboardingEvent()

    /** Persist completion and signal [OnboardingUiState.isComplete]. Called from the last step. */
    object CompleteOnboarding : OnboardingEvent()
}

// ── ViewModel ────────────────────────────────────────────────────────────────────────────────

/**
 * Drives the onboarding step state machine (spec §2.14, Standards §3,§4,§8).
 *
 * All platform-specific detection is injectable ([oemName], [sdkVersion]) so the step sequence
 * and OEM detection are fully unit-testable without Robolectric (DECISIONS.md S6-A3).
 *
 * The test-reminder trigger is a suspend lambda ([scheduleTestReminder]) rather than the full
 * [AlarmCoordinator] so tests can verify it is called without constructing the alarm engine.
 */
class OnboardingViewModel(
    private val repository: OnboardingRepository,
    private val oemName: String = Build.MANUFACTURER,
    private val sdkVersion: Int = Build.VERSION.SDK_INT,
    private val scheduleTestReminder: suspend () -> Unit,
) : ViewModel() {

    /** Ordered list of steps computed once at construction (OEM + API-level dependent). */
    internal val steps: List<OnboardingStep> = buildSteps(oemName, sdkVersion)

    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            currentStep = steps.first(),
            totalSteps = steps.size,
            currentStepIndex = 0,
            oemName = oemName,
        ),
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun onEvent(event: OnboardingEvent) {
        when (event) {
            is OnboardingEvent.NextStep -> advanceStep()
            is OnboardingEvent.PreviousStep -> goBackStep()
            is OnboardingEvent.SendTestReminder -> sendTestReminder()
            is OnboardingEvent.CompleteOnboarding -> completeOnboarding()
        }
    }

    private fun advanceStep() {
        val nextIndex = _uiState.value.currentStepIndex + 1
        if (nextIndex < steps.size) {
            _uiState.update {
                it.copy(
                    currentStep = steps[nextIndex],
                    currentStepIndex = nextIndex,
                )
            }
        }
    }

    private fun goBackStep() {
        val prevIndex = _uiState.value.currentStepIndex - 1
        if (prevIndex >= 0) {
            _uiState.update {
                it.copy(
                    currentStep = steps[prevIndex],
                    currentStepIndex = prevIndex,
                )
            }
        }
    }

    /**
     * Schedules the test alarm via the Slice 4 engine. Best-effort: if the alarm engine is in
     * windowed-fallback mode the notification still arrives; if it outright fails (e.g. no
     * upcoming db rows during cold install) the confirmation is still shown so the user can
     * proceed. Failure is logged (class only, no PHI) and not propagated.
     */
    private fun sendTestReminder() {
        viewModelScope.launch {
            runCatching {
                scheduleTestReminder()
            }.onFailure { throwable ->
                // Log class only; no message (may contain system details, not PHI-safe).
                android.util.Log.w("OnboardingViewModel", "Test reminder failed: ${throwable.javaClass.simpleName}")
            }
            // Mark sent regardless of outcome so the user can proceed with onboarding.
            _uiState.update { it.copy(testReminderSent = true) }
        }
    }

    private fun completeOnboarding() {
        viewModelScope.launch {
            repository.markComplete()
            _uiState.update { it.copy(isComplete = true) }
        }
    }

    companion object {

        /**
         * Builds the ordered step sequence for a given OEM and API level. Extracted as a named
         * companion function so unit tests can assert on it directly.
         */
        internal fun buildSteps(oemName: String, sdkVersion: Int): List<OnboardingStep> =
            buildList {
                add(OnboardingStep.WELCOME)
                if (sdkVersion >= Build.VERSION_CODES.TIRAMISU) {
                    add(OnboardingStep.NOTIFICATION_PERMISSION)
                }
                add(OnboardingStep.EXACT_ALARM_PERMISSION)
                add(OnboardingStep.BATTERY_OPTIMIZATION)
                if (isKillerOem(oemName)) {
                    add(OnboardingStep.AUTO_START)
                }
                add(OnboardingStep.TEST_REMINDER)
            }

        /**
         * Returns true for the four OEM brands known to impose aggressive background-process
         * restrictions that can prevent dose alarms from firing (D5 — dontkillmyapp.com list).
         */
        internal fun isKillerOem(name: String): Boolean =
            name.lowercase(Locale.ROOT) in setOf("xiaomi", "oppo", "vivo", "honor")

        fun factory(
            repository: OnboardingRepository,
            alarmCoordinator: AlarmCoordinator,
        ) = viewModelFactory {
            initializer {
                OnboardingViewModel(
                    repository = repository,
                    scheduleTestReminder = { alarmCoordinator.scheduleTestReminder() },
                )
            }
        }
    }
}
