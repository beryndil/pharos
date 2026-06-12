package com.beryndil.pharos.onboarding

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the onboarding step state machine (spec §2.14, DECISIONS.md S6-A3).
 *
 * All Android platform dependencies are replaced by simple fakes or injected values so the
 * tests run on the JVM without Robolectric. [Dispatchers.Main] is swapped for the test
 * dispatcher so [OnboardingViewModel.viewModelScope] is runnable in [runTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private fun makeVm(
        oemName: String = "Google",
        sdkVersion: Int = Build.VERSION_CODES.TIRAMISU,
        initialComplete: Boolean = false,
        testReminderError: Boolean = false,
    ): OnboardingViewModel {
        val repo = FakeOnboardingRepository(initialComplete)
        return OnboardingViewModel(
            repository = repo,
            oemName = oemName,
            sdkVersion = sdkVersion,
            scheduleTestReminder = {
                if (testReminderError) error("simulated alarm failure")
            },
        )
    }

    // ── buildSteps: step-list construction ────────────────────────────────────────────────────

    @Test
    fun buildSteps_api33NonOem_includesNotification_excludesAutoStart() {
        val steps = OnboardingViewModel.buildSteps("Google", Build.VERSION_CODES.TIRAMISU)
        assertTrue(steps.contains(OnboardingStep.NOTIFICATION_PERMISSION))
        assertFalse(steps.contains(OnboardingStep.AUTO_START))
    }

    @Test
    fun buildSteps_api26_excludesNotification() {
        val steps = OnboardingViewModel.buildSteps("Google", Build.VERSION_CODES.O)
        assertFalse(steps.contains(OnboardingStep.NOTIFICATION_PERMISSION))
    }

    @Test
    fun buildSteps_xiaomi_includesAutoStart() {
        val steps = OnboardingViewModel.buildSteps("Xiaomi", Build.VERSION_CODES.TIRAMISU)
        assertTrue(steps.contains(OnboardingStep.AUTO_START))
    }

    @Test
    fun buildSteps_oppo_includesAutoStart() {
        val steps = OnboardingViewModel.buildSteps("OPPO", Build.VERSION_CODES.TIRAMISU)
        assertTrue(steps.contains(OnboardingStep.AUTO_START))
    }

    @Test
    fun buildSteps_vivo_includesAutoStart() {
        val steps = OnboardingViewModel.buildSteps("vivo", Build.VERSION_CODES.TIRAMISU)
        assertTrue(steps.contains(OnboardingStep.AUTO_START))
    }

    @Test
    fun buildSteps_honor_includesAutoStart() {
        val steps = OnboardingViewModel.buildSteps("Honor", Build.VERSION_CODES.TIRAMISU)
        assertTrue(steps.contains(OnboardingStep.AUTO_START))
    }

    @Test
    fun buildSteps_samsung_excludesAutoStart() {
        val steps = OnboardingViewModel.buildSteps("samsung", Build.VERSION_CODES.TIRAMISU)
        assertFalse(steps.contains(OnboardingStep.AUTO_START))
    }

    @Test
    fun buildSteps_alwaysStartsWithWelcome_endsWithTestReminder() {
        val steps = OnboardingViewModel.buildSteps("Google", Build.VERSION_CODES.TIRAMISU)
        assertEquals(OnboardingStep.WELCOME, steps.first())
        assertEquals(OnboardingStep.TEST_REMINDER, steps.last())
    }

    // ── isKillerOem: case-insensitive matching ────────────────────────────────────────────────

    @Test
    fun isKillerOem_matchesCaseInsensitive() {
        assertTrue(OnboardingViewModel.isKillerOem("XIAOMI"))
        assertTrue(OnboardingViewModel.isKillerOem("Xiaomi"))
        assertTrue(OnboardingViewModel.isKillerOem("xiaomi"))
        assertTrue(OnboardingViewModel.isKillerOem("OPPO"))
        assertTrue(OnboardingViewModel.isKillerOem("VIVO"))
        assertTrue(OnboardingViewModel.isKillerOem("Honor"))
    }

    @Test
    fun isKillerOem_returnsFalseForOthers() {
        assertFalse(OnboardingViewModel.isKillerOem("Google"))
        assertFalse(OnboardingViewModel.isKillerOem("samsung"))
        assertFalse(OnboardingViewModel.isKillerOem("motorola"))
        assertFalse(OnboardingViewModel.isKillerOem(""))
    }

    // ── State machine: step advancement ───────────────────────────────────────────────────────

    @Test
    fun nextStep_advancesFromWelcome() {
        val vm = makeVm()
        assertEquals(OnboardingStep.WELCOME, vm.uiState.value.currentStep)
        vm.onEvent(OnboardingEvent.NextStep)
        assertEquals(OnboardingStep.NOTIFICATION_PERMISSION, vm.uiState.value.currentStep)
    }

    @Test
    fun nextStep_advancesSequentially_toLastStep() {
        val vm = makeVm(oemName = "Google", sdkVersion = Build.VERSION_CODES.TIRAMISU)
        val steps = vm.steps
        for (i in 1 until steps.size) {
            vm.onEvent(OnboardingEvent.NextStep)
            assertEquals(steps[i], vm.uiState.value.currentStep)
        }
        // One more NextStep at the last step: should not crash and step stays the same.
        vm.onEvent(OnboardingEvent.NextStep)
        assertEquals(steps.last(), vm.uiState.value.currentStep)
    }

    @Test
    fun totalSteps_matchesStepListSize() {
        val vm = makeVm(oemName = "Xiaomi", sdkVersion = Build.VERSION_CODES.TIRAMISU)
        assertEquals(vm.steps.size, vm.uiState.value.totalSteps)
    }

    @Test
    fun currentStepIndex_incrementsWithNextStep() {
        val vm = makeVm()
        assertEquals(0, vm.uiState.value.currentStepIndex)
        vm.onEvent(OnboardingEvent.NextStep)
        assertEquals(1, vm.uiState.value.currentStepIndex)
    }

    @Test
    fun previousStep_returnsToPriorStep() {
        val vm = makeVm()
        vm.onEvent(OnboardingEvent.NextStep)
        assertEquals(OnboardingStep.NOTIFICATION_PERMISSION, vm.uiState.value.currentStep)
        vm.onEvent(OnboardingEvent.PreviousStep)
        assertEquals(OnboardingStep.WELCOME, vm.uiState.value.currentStep)
        assertEquals(0, vm.uiState.value.currentStepIndex)
    }

    @Test
    fun previousStep_onFirstStep_isNoOp() {
        val vm = makeVm()
        assertEquals(0, vm.uiState.value.currentStepIndex)
        vm.onEvent(OnboardingEvent.PreviousStep)
        assertEquals(OnboardingStep.WELCOME, vm.uiState.value.currentStep)
        assertEquals(0, vm.uiState.value.currentStepIndex)
    }

    // ── Test reminder ─────────────────────────────────────────────────────────────────────────

    @Test
    fun sendTestReminder_setsTestReminderSentTrue() = runTest {
        val vm = makeVm()
        assertFalse(vm.uiState.value.testReminderSent)
        vm.onEvent(OnboardingEvent.SendTestReminder)
        assertTrue(vm.uiState.value.testReminderSent)
    }

    @Test
    fun sendTestReminder_setsTestReminderSentTrue_evenOnError() = runTest {
        val vm = makeVm(testReminderError = true)
        vm.onEvent(OnboardingEvent.SendTestReminder)
        // Best-effort: testReminderSent must be true so the user can proceed (spec §2.14).
        assertTrue(vm.uiState.value.testReminderSent)
    }

    // ── Completion ────────────────────────────────────────────────────────────────────────────

    @Test
    fun completeOnboarding_setsIsCompleteTrue_andPersists() = runTest {
        val repo = FakeOnboardingRepository(initialComplete = false)
        val vm = OnboardingViewModel(
            repository = repo,
            oemName = "Google",
            sdkVersion = Build.VERSION_CODES.TIRAMISU,
            scheduleTestReminder = {},
        )
        assertFalse(vm.uiState.value.isComplete)
        vm.onEvent(OnboardingEvent.CompleteOnboarding)
        assertTrue(vm.uiState.value.isComplete)
        assertTrue(repo.isComplete()) // persisted to the fake repository
    }

    // ── Later-launch skip: repository already complete ────────────────────────────────────────

    @Test
    fun repository_isComplete_returnsTrue_afterMarkComplete() = runTest {
        val repo = FakeOnboardingRepository(initialComplete = false)
        assertFalse(repo.isComplete())
        repo.markComplete()
        assertTrue(repo.isComplete())
    }
}

// ── Fakes ─────────────────────────────────────────────────────────────────────────────────────

/**
 * In-memory [OnboardingRepository] substitute for unit tests. Implements the same contract
 * without needing a Room database or Robolectric context.
 */
private class FakeOnboardingRepository(initialComplete: Boolean) : OnboardingRepository(
    // OnboardingRepository is a concrete class that takes a SettingDao — we can't easily
    // subclass it without a DAO. Instead we use composition via a companion test class.
    settingDao = FakeSettingDaoForOnboarding(),
) {
    // Override state directly — the fake DAO is irrelevant here.
    private var _complete = initialComplete

    override suspend fun isComplete(): Boolean = _complete

    override suspend fun markComplete(nowMs: Long) {
        _complete = true
    }
}

/** Minimal no-op SettingDao used only to satisfy the OnboardingRepository constructor. */
private class FakeSettingDaoForOnboarding : com.beryndil.pharos.data.regimen.dao.SettingDao {
    private val map = mutableMapOf<String, com.beryndil.pharos.data.regimen.entity.SettingEntity>()

    override suspend fun upsert(setting: com.beryndil.pharos.data.regimen.entity.SettingEntity) {
        map[setting.key] = setting
    }

    override suspend fun get(key: String) = map[key]

    override fun observeAll(): kotlinx.coroutines.flow.Flow<List<com.beryndil.pharos.data.regimen.entity.SettingEntity>> =
        kotlinx.coroutines.flow.MutableStateFlow(map.values.toList())

    override suspend fun getAll(): List<com.beryndil.pharos.data.regimen.entity.SettingEntity> =
        map.values.toList()
}
