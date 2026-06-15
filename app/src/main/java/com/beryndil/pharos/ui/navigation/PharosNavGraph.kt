package com.beryndil.pharos.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import android.app.NotificationManager
import android.content.Context
import com.beryndil.pharos.PharosApplication
import com.beryndil.pharos.appContainer
import com.beryndil.pharos.dose.ui.DoseHistoryScreen
import com.beryndil.pharos.dose.ui.DoseHistoryViewModel
import com.beryndil.pharos.dose.ui.TodayScreen
import com.beryndil.pharos.dose.ui.TodayViewModel
import com.beryndil.pharos.medication.AddEditMedicationViewModel
import com.beryndil.pharos.medication.MedicationListViewModel
import com.beryndil.pharos.medication.ui.AddEditMedicationScreen
import com.beryndil.pharos.medication.ui.MedicationListScreen
import com.beryndil.pharos.onboarding.OnboardingViewModel
import com.beryndil.pharos.onboarding.ui.OnboardingScreen
import com.beryndil.pharos.refill.RefillViewModel
import com.beryndil.pharos.refill.ui.RefillDetailScreen
import com.beryndil.pharos.reference.DrugReferenceViewModel
import com.beryndil.pharos.reference.ui.DrugReferenceScreen
import com.beryndil.pharos.backup.AutoBackupManager
import com.beryndil.pharos.backup.BackupViewModel
import com.beryndil.pharos.contacts.ContactsEvent
import com.beryndil.pharos.contacts.SavedContactsScreen
import com.beryndil.pharos.contacts.SavedContactsViewModel
import com.beryndil.pharos.backup.ui.BackupScreen
import com.beryndil.pharos.legal.ui.LegalScreen
import com.beryndil.pharos.reliability.ReliabilityDashboardViewModel
import com.beryndil.pharos.reliability.ui.ReliabilityDashboardScreen
import com.beryndil.pharos.settings.SettingsViewModel
import com.beryndil.pharos.settings.UserProfileViewModel
import com.beryndil.pharos.settings.ui.AboutScreen
import com.beryndil.pharos.settings.ui.LicenseScreen
import com.beryndil.pharos.settings.ui.SettingsScreen
import com.beryndil.pharos.settings.ui.UserProfileScreen

/**
 * The Pharos nav graph.
 *
 * [startDestination] is resolved by [com.beryndil.pharos.MainActivity] via an async read of the
 * onboarding completion flag so first-time users see [NavRoute.Onboarding] and returning users
 * go straight to [NavRoute.Today].
 *
 * All ViewModels are created with manual DI factories sourced from
 * [PharosApplication.appContainer] (DECISIONS.md A1 — no Hilt/Dagger in v1).
 */
@Composable
fun PharosNavGraph(
    navController: NavHostController,
    startDestination: String = NavRoute.Today.route,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as PharosApplication
    val nm = LocalContext.current.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val medicationRepository = app.appContainer.medicationRepository
    val scheduleRepository = app.appContainer.scheduleRepository
    val doseRepository = app.appContainer.doseRepository
    val refillRepository = app.appContainer.refillRepository
    val drugLabelRepository = app.appContainer.drugLabelRepository
    val backupRepository = app.appContainer.backupRepository
    val autoBackupManager = app.appContainer.autoBackupManager
    val contactRepository = app.appContainer.contactRepository

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {

        // ── Onboarding (first launch only) ────────────────────────────────
        composable(NavRoute.Onboarding.route) {
            val viewModel: OnboardingViewModel = viewModel(
                factory = OnboardingViewModel.factory(
                    repository = app.appContainer.onboardingRepository,
                    alarmCoordinator = app.appContainer.alarmCoordinator,
                ),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            OnboardingScreen(
                uiState = uiState,
                onEvent = viewModel::onEvent,
                onDone = {
                    // After onboarding the regimen is empty, so land on the medication list — it
                    // shows the "No medications / Restore from backup" empty state with the add (+)
                    // action, the natural first-run home. Onboarding is cleared from the back stack.
                    navController.navigate(NavRoute.MedicationList.route) {
                        popUpTo(NavRoute.Onboarding.route) { inclusive = true }
                    }
                },
                onOpenLegal = { navController.navigate(NavRoute.Legal.route) },
            )
        }

        // ── Today (enriched home surface — F3 + F4) ───────────────────────
        composable(NavRoute.Today.route) {
            val viewModel: TodayViewModel = viewModel(
                factory = TodayViewModel.factory(
                    doseRepository        = doseRepository,
                    pdfExporter           = app.appContainer.medListPdfExporter,
                    cacheDir              = app.cacheDir,
                    userProfileRepository = app.appContainer.userProfileRepository,
                ),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            TodayScreen(
                uiState = uiState,
                onEvent = viewModel::onEvent,
                onOpenMedications = { navController.navigate(NavRoute.MedicationList.route) },
                onOpenHistory = { medId ->
                    navController.navigate(NavRoute.DoseHistory.buildRoute(medId))
                },
                onOpenReliability = {
                    navController.navigate(NavRoute.ReliabilityDashboard.route)
                },
                onOpenSettings = { navController.navigate(NavRoute.Settings.route) },
            )
        }

        // ── Per-med dose history (append-only) ────────────────────────────
        composable(
            route = NavRoute.DoseHistory.route,
            arguments = listOf(
                navArgument(NavRoute.DoseHistory.ARG_MED_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val medId =
                backStackEntry.arguments?.getString(NavRoute.DoseHistory.ARG_MED_ID).orEmpty()
            val viewModel: DoseHistoryViewModel = viewModel(
                factory = DoseHistoryViewModel.factory(
                    doseRepository = doseRepository,
                    medicationId = medId,
                ),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            DoseHistoryScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
            )
        }

        // ── Medication list ───────────────────────────────────────────────
        composable(NavRoute.MedicationList.route) {
            val viewModel: MedicationListViewModel = viewModel(
                factory = MedicationListViewModel.factory(
                    medicationRepository = medicationRepository,
                    scheduleRepository = scheduleRepository,
                ),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            MedicationListScreen(
                uiState = uiState,
                onAddMedication = {
                    navController.navigate(NavRoute.AddMedication.route)
                },
                onMedicationClicked = { medId ->
                    navController.navigate(NavRoute.EditMedication.buildRoute(medId))
                },
                onRefillClicked = { medId ->
                    navController.navigate(NavRoute.RefillDetail.buildRoute(medId))
                },
                onDrugReferenceClicked = { medId ->
                    navController.navigate(NavRoute.DrugReference.buildRoute(medId))
                },
                onOpenSavedContacts = {
                    navController.navigate(NavRoute.SavedContacts.route)
                },
                onOpenBackup = {
                    navController.navigate(NavRoute.BackupRestore.route)
                },
                onOpenLegal = { navController.navigate(NavRoute.Legal.route) },
                onOpenSettings = { navController.navigate(NavRoute.Settings.route) },
                onEvent = viewModel::onEvent,
            )
        }

        // ── Add medication ────────────────────────────────────────────────
        composable(NavRoute.AddMedication.route) {
            val viewModel: AddEditMedicationViewModel = viewModel(
                factory = AddEditMedicationViewModel.factory(
                    repository = medicationRepository,
                    scheduleRepository = scheduleRepository,
                    contactRepository = contactRepository,
                    drugLabelRepository = drugLabelRepository,
                    isDndAccessGranted = { nm.isNotificationPolicyAccessGranted },
                ),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            AddEditMedicationScreen(
                uiState = uiState,
                onEvent = viewModel::onEvent,
                onDone = { navController.popBackStack() },
            )
        }

        // ── Edit medication ───────────────────────────────────────────────
        composable(
            route = NavRoute.EditMedication.route,
            arguments = listOf(
                navArgument(NavRoute.EditMedication.ARG_MED_ID) {
                    type = NavType.StringType
                },
            ),
        ) {
            // SavedStateHandle in AddEditMedicationViewModel picks up medId automatically
            // because navigation-compose wires nav args into CreationExtras.
            val viewModel: AddEditMedicationViewModel = viewModel(
                factory = AddEditMedicationViewModel.factory(
                    repository = medicationRepository,
                    scheduleRepository = scheduleRepository,
                    contactRepository = contactRepository,
                    drugLabelRepository = drugLabelRepository,
                    isDndAccessGranted = { nm.isNotificationPolicyAccessGranted },
                ),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            AddEditMedicationScreen(
                uiState = uiState,
                onEvent = viewModel::onEvent,
                onDone = { navController.popBackStack() },
            )
        }

        // ── Refill tracking (Slice 7, spec §2.9) ─────────────────────────
        composable(
            route = NavRoute.RefillDetail.route,
            arguments = listOf(
                navArgument(NavRoute.RefillDetail.ARG_MED_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val medId = backStackEntry.arguments?.getString(NavRoute.RefillDetail.ARG_MED_ID).orEmpty()
            val viewModel: RefillViewModel = viewModel(
                factory = RefillViewModel.factory(
                    refillRepository = refillRepository,
                    medicationId = medId,
                ),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            RefillDetailScreen(
                uiState = uiState,
                onEvent = viewModel::onEvent,
                onBack = { navController.popBackStack() },
            )
        }

        // ── Drug reference (Slice 8, spec §2.10 — accessible from med list) ──
        composable(
            route = NavRoute.DrugReference.route,
            arguments = listOf(
                navArgument(NavRoute.DrugReference.ARG_MED_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val medId = backStackEntry.arguments?.getString(NavRoute.DrugReference.ARG_MED_ID).orEmpty()
            val viewModel: DrugReferenceViewModel = viewModel(
                factory = DrugReferenceViewModel.factory(
                    medicationId = medId,
                    medicationDao = app.appContainer.regimenDatabase.medicationDao(),
                    drugLabelRepository = drugLabelRepository,
                ),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            DrugReferenceScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
            )
        }

        // ── Backup / restore / export (Slice 9, spec §2.12) ─────────────
        composable(NavRoute.BackupRestore.route) {
            val viewModel: BackupViewModel = viewModel(
                factory = BackupViewModel.factory(
                    repository = backupRepository,
                    autoBackupManager = autoBackupManager,
                ),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            BackupScreen(
                uiState = uiState,
                onEvent = viewModel::onEvent,
                onBack = { navController.popBackStack() },
            )
        }

        // ── Saved Contacts manage screen (V1.3-F1) ─────────────────────────
        composable(NavRoute.SavedContacts.route) {
            val viewModel: SavedContactsViewModel = viewModel(
                factory = SavedContactsViewModel.factory(repository = contactRepository),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            SavedContactsScreen(
                uiState = uiState,
                onEvent = viewModel::onEvent,
                onBack = { navController.popBackStack() },
            )
        }

        // ── Legal (spec §4.2 — ToS, Privacy Policy, Medical Disclaimer) ─────
        composable(NavRoute.Legal.route) {
            LegalScreen(onBack = { navController.popBackStack() })
        }

        // ── Settings (A5-S1 — theme, text size, about, legal) ───────────
        composable(NavRoute.Settings.route) {
            val viewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory(
                    appearanceRepository = app.appContainer.appearanceRepository,
                ),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            SettingsScreen(
                uiState = uiState,
                onEvent = viewModel::onEvent,
                onOpenAbout = { navController.navigate(NavRoute.About.route) },
                onOpenLegal = { navController.navigate(NavRoute.Legal.route) },
                onOpenProfile = { navController.navigate(NavRoute.UserProfile.route) },
                onBack = { navController.popBackStack() },
            )
        }

        // ── User profile (PDF export header) ─────────────────────────────
        composable(NavRoute.UserProfile.route) {
            val viewModel: UserProfileViewModel = viewModel(
                factory = UserProfileViewModel.factory(
                    repository = app.appContainer.userProfileRepository,
                ),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            UserProfileScreen(
                uiState = uiState,
                onEvent = viewModel::onEvent,
                onBack = { navController.popBackStack() },
            )
        }

        // ── About (A5-S1 — identity, version, data attributions) ─────────
        composable(NavRoute.About.route) {
            AboutScreen(
                onOpenLicense = { navController.navigate(NavRoute.License.route) },
                onOpenLegal = { navController.navigate(NavRoute.Legal.route) },
                onBack = { navController.popBackStack() },
            )
        }

        // ── License and credits (A5-S1 — hand-written OSS + data credits) ─
        composable(NavRoute.License.route) {
            LicenseScreen(onBack = { navController.popBackStack() })
        }

        // ── Reliability dashboard (Law 6 — reliability is visible) ────────
        composable(NavRoute.ReliabilityDashboard.route) {
            val viewModel: ReliabilityDashboardViewModel = viewModel(
                factory = ReliabilityDashboardViewModel.factory(
                    settingDao = app.appContainer.regimenDatabase.settingDao(),
                    medicationDao = app.appContainer.regimenDatabase.medicationDao(),
                    applicationContext = app.applicationContext,
                    doseNotifier = app.appContainer.doseNotifier,
                ),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            ReliabilityDashboardScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                onTestCriticalAlert = viewModel::onTestCriticalAlert,
                onRefreshPermissions = viewModel::refreshPermissions,
            )
        }
    }
}

/** Sealed route definitions keep all route strings in one place. */
sealed class NavRoute(val route: String) {

    data object Onboarding : NavRoute("onboarding")

    data object Today : NavRoute("today")

    data object DoseHistory : NavRoute("medications/{medId}/history") {
        const val ARG_MED_ID = "medId"

        fun buildRoute(medId: String): String = "medications/$medId/history"
    }

    data object MedicationList : NavRoute("medications")

    data object AddMedication : NavRoute("medications/add")

    data object EditMedication : NavRoute("medications/{medId}/edit") {
        // "medId" literal matches the route template above; do not change independently.
        const val ARG_MED_ID = "medId"

        fun buildRoute(medId: String): String = "medications/$medId/edit"
    }

    data object ReliabilityDashboard : NavRoute("reliability")

    data object RefillDetail : NavRoute("medications/{medId}/refill") {
        const val ARG_MED_ID = "medId"

        fun buildRoute(medId: String): String = "medications/$medId/refill"
    }

    data object DrugReference : NavRoute("medications/{medId}/reference") {
        const val ARG_MED_ID = "medId"

        fun buildRoute(medId: String): String = "medications/$medId/reference"
    }

    data object BackupRestore : NavRoute("backup")

    data object Legal : NavRoute("legal")

    data object SavedContacts : NavRoute("settings/saved-contacts")

    data object Settings : NavRoute("settings")

    data object About : NavRoute("settings/about")

    data object License : NavRoute("settings/license")

    data object UserProfile : NavRoute("settings/profile")
}
