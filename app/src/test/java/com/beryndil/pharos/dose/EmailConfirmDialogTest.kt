package com.beryndil.pharos.dose

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.alarm.DoseNotifier
import com.beryndil.pharos.data.dose.DoseRepository
import com.beryndil.pharos.data.regimen.RegimenDatabase
import com.beryndil.pharos.dose.ui.TodayEvent
import com.beryndil.pharos.dose.ui.TodayViewModel
import com.beryndil.pharos.medication.export.MedListPdfExporter
import com.beryndil.pharos.settings.UserProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Tests the confirm-dialog state-machine in [TodayViewModel] for the F4 email flow.
 *
 * Verifies that:
 *  - [TodayEvent.EmailMedListRequest] shows the confirm dialog (Law 4 gate before data leaves).
 *  - [TodayEvent.EmailMedListDismiss] closes the dialog without generating a PDF.
 *  - [TodayEvent.EmailMedListConfirm] closes the dialog and triggers PDF generation.
 *  - [TodayEvent.EmailMedListIntentConsumed] clears [TodayUiState.pendingEmailFile].
 *
 * Tests use [TodayViewModel.emailConfirmVisible] / [TodayViewModel.pendingEmailFileRaw] — the
 * internal test accessors on the underlying email state — to bypass the Room-flow emission
 * dependency in [TodayViewModel.uiState] (which requires all source flows to emit before
 * the combine updates; see DECISIONS.md F3F4-A6). PDF-generating paths are @Ignored (tracked
 * in TODO.md §C F3F4-C2 as device-only because PdfDocument requires native Android rendering).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailConfirmDialogTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testNowMs = 1_749_729_600_000L
    private val testZone  = ZoneId.of("UTC")

    private lateinit var db: RegimenDatabase
    private lateinit var viewModel: TodayViewModel

    private val noOpNotifier = object : DoseNotifier {
        override fun ensureChannels() = Unit
        override fun postDoseDueAlert(doseId: String, medName: String, dueEpochMs: Long) = Unit
        override fun postTestReminder() = Unit
        override fun postTestCriticalReminder() = Unit
        override fun canUseFullScreen() = false
    }

    private val noOpTransitionScheduler = object : DoseTransitionScheduler {
        override fun scheduleMissCheck(doseId: String, triggerAtEpochMs: Long) = Unit
        override fun scheduleReAlert(doseId: String, triggerAtEpochMs: Long) = Unit
        override fun cancelTimers(doseId: String) = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, RegimenDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val stateMachine = DoseStateMachine(
            doseInstanceDao     = db.doseInstanceDao(),
            doseTransitionDao   = db.doseTransitionDao(),
            medicationDao       = db.medicationDao(),
            scheduleDao         = db.scheduleDao(),
            transitionScheduler = noOpTransitionScheduler,
            notifier            = noOpNotifier,
            now                 = { testNowMs },
            zoneProvider        = { testZone },
        )
        val doseRepository = DoseRepository(
            doseInstanceDao   = db.doseInstanceDao(),
            doseTransitionDao = db.doseTransitionDao(),
            medicationDao     = db.medicationDao(),
            scheduleDao       = db.scheduleDao(),
            stateMachine      = stateMachine,
            now               = { testNowMs },
            zoneProvider      = { testZone },
        )
        val cacheDir = createTempDir("pharos-email-confirm-test")
        viewModel = TodayViewModel(
            doseRepository = doseRepository,
            pdfExporter    = MedListPdfExporter(db),
            cacheDir       = cacheDir,
            userProfileRepository = UserProfileRepository(db.settingDao()),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    // ── Tests — email dialog state machine ───────────────────────────────
    //
    // These tests use TodayViewModel.emailConfirmVisible / pendingEmailFileRaw — the
    // internal test accessors on _emailState — to sidestep the Room-flow emission timing
    // issue with the combine-based uiState (see class-level KDoc above).

    @Test
    fun `confirm dialog is hidden by default`() = runTest {
        assertFalse(viewModel.emailConfirmVisible)
    }

    @Test
    fun `EmailMedListRequest opens the confirm dialog`() = runTest {
        viewModel.onEvent(TodayEvent.EmailMedListRequest)
        assertTrue(viewModel.emailConfirmVisible)
    }

    @Test
    fun `EmailMedListDismiss closes the confirm dialog`() = runTest {
        viewModel.onEvent(TodayEvent.EmailMedListRequest)
        assertTrue(viewModel.emailConfirmVisible)
        viewModel.onEvent(TodayEvent.EmailMedListDismiss)
        assertFalse(viewModel.emailConfirmVisible)
    }

    @Test
    fun `EmailMedListDismiss does not generate a PDF`() = runTest {
        viewModel.onEvent(TodayEvent.EmailMedListRequest)
        viewModel.onEvent(TodayEvent.EmailMedListDismiss)
        assertNull("No PDF file should be pending after dismiss", viewModel.pendingEmailFileRaw)
    }

    @Test
    fun `EmailMedListConfirm closes the confirm dialog`() = runTest {
        viewModel.onEvent(TodayEvent.EmailMedListRequest)
        assertTrue(viewModel.emailConfirmVisible)
        viewModel.onEvent(TodayEvent.EmailMedListConfirm)
        assertFalse(viewModel.emailConfirmVisible)
    }

    @Ignore("EmailMedListConfirm triggers PdfDocument which requires on-device native rendering — tracked in TODO.md §C F3F4-C2")
    @Test
    fun `EmailMedListConfirm sets pendingEmailFile after PDF generation`() = runTest {
        viewModel.onEvent(TodayEvent.EmailMedListRequest)
        viewModel.onEvent(TodayEvent.EmailMedListConfirm)
        assertTrue("pendingEmailFileRaw should be set after confirm", viewModel.pendingEmailFileRaw != null)
    }

    @Ignore("EmailMedListConfirm triggers PdfDocument which requires on-device native rendering — tracked in TODO.md §C F3F4-C2")
    @Test
    fun `EmailMedListIntentConsumed clears pendingEmailFile`() = runTest {
        viewModel.onEvent(TodayEvent.EmailMedListRequest)
        viewModel.onEvent(TodayEvent.EmailMedListConfirm)
        viewModel.onEvent(TodayEvent.EmailMedListIntentConsumed)
        assertNull("pendingEmailFileRaw should be null after intent consumed", viewModel.pendingEmailFileRaw)
    }
}
