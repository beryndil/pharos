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
import com.beryndil.pharos.reliability.ReliabilityDashboardViewModel
import com.beryndil.pharos.reliability.ui.ReliabilityDashboardScreen

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
    val medicationRepository = app.appContainer.medicationRepository
    val scheduleRepository = app.appContainer.scheduleRepository
    val doseRepository = app.appContainer.doseRepository
    val refillRepository = app.appContainer.refillRepository

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
                    navController.navigate(NavRoute.Today.route) {
                        popUpTo(NavRoute.Onboarding.route) { inclusive = true }
                    }
                },
            )
        }

        // ── Today (actionable dose surface) ───────────────────────────────
        composable(NavRoute.Today.route) {
            val viewModel: TodayViewModel = viewModel(
                factory = TodayViewModel.factory(doseRepository = doseRepository),
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
                onEvent = viewModel::onEvent,
            )
        }

        // ── Add medication ────────────────────────────────────────────────
        composable(NavRoute.AddMedication.route) {
            val viewModel: AddEditMedicationViewModel = viewModel(
                factory = AddEditMedicationViewModel.factory(
                    repository = medicationRepository,
                    scheduleRepository = scheduleRepository,
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

        // ── Reliability dashboard (Law 6 — reliability is visible) ────────
        composable(NavRoute.ReliabilityDashboard.route) {
            val viewModel: ReliabilityDashboardViewModel = viewModel(
                factory = ReliabilityDashboardViewModel.factory(
                    settingDao = app.appContainer.regimenDatabase.settingDao(),
                    applicationContext = app.appContainer.regimenDatabase.let {
                        // Use applicationContext captured via the app reference (no Activity leak).
                        app.applicationContext
                    },
                ),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            ReliabilityDashboardScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
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
}
