package com.beryndil.pharos.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Pharos nav graph.
 *
 * All routes are declared here; ViewModels are created with manual DI factories sourced from
 * [PharosApplication.appContainer] (DECISIONS.md A1 — no Hilt/Dagger in v1).
 */
@Composable
fun PharosNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as PharosApplication
    val medicationRepository = app.appContainer.medicationRepository
    val scheduleRepository = app.appContainer.scheduleRepository
    val doseRepository = app.appContainer.doseRepository

    NavHost(
        navController = navController,
        startDestination = NavRoute.Today.route,
        modifier = modifier,
    ) {
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
            )
        }

        // ── Per-med dose history (append-only) ────────────────────────────
        composable(
            route = NavRoute.DoseHistory.route,
            arguments = listOf(
                navArgument(NavRoute.DoseHistory.ARG_MED_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val medId = backStackEntry.arguments?.getString(NavRoute.DoseHistory.ARG_MED_ID).orEmpty()
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
    }
}

/** Sealed route definitions keep all route strings in one place. */
sealed class NavRoute(val route: String) {

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
}
