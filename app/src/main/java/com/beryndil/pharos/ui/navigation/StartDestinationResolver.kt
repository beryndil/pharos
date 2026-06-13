package com.beryndil.pharos.ui.navigation

/**
 * Resolves the initial navigation destination on app launch (spec §2.14, Slice 6).
 *
 * Three-way routing:
 *  - Onboarding not complete → [NavRoute.Onboarding] (first launch)
 *  - Onboarding complete, zero non-ended medications → [NavRoute.MedicationList]
 *    (empty-state with the + FAB — user adds their first medication)
 *  - Onboarding complete, at least one non-ended medication → [NavRoute.Today]
 *
 * Extracted as a top-level suspend function so it can be unit-tested on the JVM
 * without an Android runtime or Compose context (DECISIONS.md S6-A4).
 *
 * @param isOnboardingComplete Suspend query: true if onboarding has been completed.
 * @param countNonEndedMedications Suspend query: count of medications with status != ENDED.
 */
suspend fun resolveStartDestination(
    isOnboardingComplete: suspend () -> Boolean,
    countNonEndedMedications: suspend () -> Int,
): String = when {
    !isOnboardingComplete() -> NavRoute.Onboarding.route
    countNonEndedMedications() == 0 -> NavRoute.MedicationList.route
    else -> NavRoute.Today.route
}
