package com.beryndil.pharos.ui.semantics

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.beryndil.pharos.alarm.AlarmMode
import com.beryndil.pharos.data.dose.DoseRow
import com.beryndil.pharos.data.regimen.entity.DoseState
import com.beryndil.pharos.dose.ui.TodayEvent
import com.beryndil.pharos.dose.ui.TodayScreen
import com.beryndil.pharos.dose.ui.TodayUiState
import com.beryndil.pharos.medication.MedicationListUiState
import com.beryndil.pharos.medication.ui.MedicationListScreen
import com.beryndil.pharos.reliability.DashboardPermissionItem
import com.beryndil.pharos.reliability.FixAction
import com.beryndil.pharos.reliability.ItemStatus
import com.beryndil.pharos.reliability.ReliabilityDashboardUiState
import com.beryndil.pharos.reliability.ui.ReliabilityDashboardScreen
import com.beryndil.pharos.reference.DrugReferenceUiState
import com.beryndil.pharos.reference.ui.DrugReferenceScreen
import com.beryndil.pharos.ui.theme.PharosTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose semantics tests for the Slice 10 accessibility gate (Law 10, Standards §8).
 *
 * These tests run on the JVM via Robolectric — no device required. They assert that:
 *  1. Every key interactive control has a non-empty, meaningful contentDescription.
 *  2. Status signals carry text (not just color) — the text is readable in the semantic tree.
 *  3. The dose action buttons on the Today screen are distinguishable by medication name.
 *
 * Lived TalkBack experience, OEM battery-killer matrix, and full font-scale rendering
 * require a real device; those are logged as Dave's on-device pass in TODO.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    // Use a plain Application so PharosApplication.onCreate() (Tink / SQLCipher init) is
    // not invoked. The stateless composables under test do not access AppContainer.
    application = Application::class,
)
class AccessibilitySemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Medication list ─────────────────────────────────────────────────────────

    /**
     * The FAB must carry a non-empty contentDescription.
     * Bug found in Slice 10 audit: FAB had contentDescription = "" which suppressed TalkBack.
     */
    @Test
    fun `medication list FAB has non-empty contentDescription`() {
        composeTestRule.setContent {
            PharosTheme {
                MedicationListScreen(
                    uiState = MedicationListUiState(),
                    onAddMedication = {},
                    onMedicationClicked = {},
                    onRefillClicked = {},
                    onDrugReferenceClicked = {},
                    onOpenBackup = {},
                    onEvent = {},
                )
            }
        }
        // "Add medication" is the string resource value of R.string.cd_add_medication.
        composeTestRule
            .onNodeWithContentDescription("Add medication")
            .assertIsDisplayed()
    }

    // ── Today / dose actions ────────────────────────────────────────────────────

    /**
     * Each dose action button must carry a contentDescription containing the medication name,
     * so TalkBack can distinguish "Mark Metformin as taken" from "Mark Lisinopril as taken"
     * when multiple dose cards are shown.
     */
    @Test
    fun `today screen Taken button description contains medication name`() {
        val medName = "Metformin"
        val dose = DoseRow(
            doseId = "d1",
            medicationId = "m1",
            medName = medName,
            strength = "500 mg",
            dueEpochMs = 1_700_000_000_000L,
            state = DoseState.DUE,
        )
        composeTestRule.setContent {
            PharosTheme {
                TodayScreen(
                    uiState = TodayUiState(doses = listOf(dose)),
                    onEvent = {},
                    onOpenMedications = {},
                    onOpenHistory = {},
                    onOpenReliability = {},
                )
            }
        }
        // Accessibility: "Mark Metformin as taken" (from R.string.cd_dose_taken_action).
        composeTestRule
            .onNode(hasContentDescription("Mark $medName as taken"))
            .assertIsDisplayed()
    }

    @Test
    fun `today screen Snooze button description contains medication name`() {
        val medName = "Lisinopril"
        val dose = DoseRow(
            doseId = "d2",
            medicationId = "m2",
            medName = medName,
            strength = "10 mg",
            dueEpochMs = 1_700_000_000_000L,
            state = DoseState.DUE,
        )
        composeTestRule.setContent {
            PharosTheme {
                TodayScreen(
                    uiState = TodayUiState(doses = listOf(dose)),
                    onEvent = {},
                    onOpenMedications = {},
                    onOpenHistory = {},
                    onOpenReliability = {},
                )
            }
        }
        composeTestRule
            .onNode(hasContentDescription("Snooze $medName"))
            .assertIsDisplayed()
    }

    @Test
    fun `today screen Skip button description contains medication name`() {
        val medName = "Atorvastatin"
        val dose = DoseRow(
            doseId = "d3",
            medicationId = "m3",
            medName = medName,
            strength = "20 mg",
            dueEpochMs = 1_700_000_000_000L,
            state = DoseState.DUE,
        )
        composeTestRule.setContent {
            PharosTheme {
                TodayScreen(
                    uiState = TodayUiState(doses = listOf(dose)),
                    onEvent = {},
                    onOpenMedications = {},
                    onOpenHistory = {},
                    onOpenReliability = {},
                )
            }
        }
        composeTestRule
            .onNode(hasContentDescription("Skip $medName"))
            .assertIsDisplayed()
    }

    // ── Reliability dashboard — no color-only signaling ─────────────────────────

    /**
     * A RISKY permission item must expose its description text in the semantic tree —
     * TalkBack must be able to read the status, not just see a colored icon.
     * Law 10 / DESIGN.md: "color never carries meaning alone; every warning is icon + text."
     */
    @Test
    fun `reliability dashboard RISKY exact alarm item exposes status text`() {
        val riskyExactAlarm = DashboardPermissionItem(
            status = ItemStatus.RISKY,
            fixAction = FixAction.ExactAlarmSettings,
        )
        composeTestRule.setContent {
            PharosTheme {
                ReliabilityDashboardScreen(
                    uiState = ReliabilityDashboardUiState(exactAlarm = riskyExactAlarm),
                    onBack = {},
                )
            }
        }
        // The RISKY description text must appear in the semantic tree (not just as a color).
        // R.string.reliability_exact_alarm_risky = "Exact timing unavailable — ..."
        composeTestRule
            .onNode(hasText("Exact timing unavailable", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun `reliability dashboard OK exact alarm item exposes status text`() {
        composeTestRule.setContent {
            PharosTheme {
                ReliabilityDashboardScreen(
                    uiState = ReliabilityDashboardUiState(),
                    onBack = {},
                )
            }
        }
        // R.string.reliability_exact_alarm_ok = "Reminders fire at the exact scheduled time"
        composeTestRule
            .onNode(hasText("Reminders fire at", substring = true))
            .assertIsDisplayed()
    }

    // ── Drug reference screen ───────────────────────────────────────────────────

    /**
     * The loading state's progress indicator must have a non-empty contentDescription
     * so TalkBack announces loading is in progress (not silence).
     * Bug fixed in Slice 10: indicator had contentDescription = "".
     */
    @Test
    fun `drug reference loading state has non-empty contentDescription`() {
        composeTestRule.setContent {
            PharosTheme {
                DrugReferenceScreen(
                    uiState = DrugReferenceUiState.Loading,
                    onBack = {},
                )
            }
        }
        // R.string.cd_loading_drug_reference = "Loading drug reference"
        composeTestRule
            .onNode(hasContentDescription("Loading drug reference"))
            .assertIsDisplayed()
    }

    /**
     * The free-text-med state must expose its explanatory text to TalkBack.
     * Bug fixed in Slice 10: the text node had contentDescription = "" which suppressed reading.
     */
    @Test
    fun `drug reference free-text state exposes explanation text`() {
        composeTestRule.setContent {
            PharosTheme {
                DrugReferenceScreen(
                    uiState = DrugReferenceUiState.FreeTextMed(medName = "Custom med"),
                    onBack = {},
                )
            }
        }
        // R.string.drug_reference_free_text = "This medication was added without a drug database match..."
        composeTestRule
            .onNode(hasText("This medication was added without", substring = true))
            .assertIsDisplayed()
    }
}
