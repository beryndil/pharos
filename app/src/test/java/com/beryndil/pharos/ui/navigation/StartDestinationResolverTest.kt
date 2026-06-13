package com.beryndil.pharos.ui.navigation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [resolveStartDestination] — the three-way start-destination logic.
 *
 * Pure-JVM: no Android, no Room, no Compose. Each test injects lambdas for the two
 * DB queries so the resolver is exercised in isolation (DECISIONS.md S6-A4).
 */
class StartDestinationResolverTest {

    @Test
    fun routesToOnboarding_whenOnboardingNotComplete() = runTest {
        val dest = resolveStartDestination(
            isOnboardingComplete = { false },
            countNonEndedMedications = { 0 },
        )
        assertEquals(NavRoute.Onboarding.route, dest)
    }

    @Test
    fun routesToOnboarding_whenNotComplete_evenWithMedications() = runTest {
        // Med count is irrelevant if onboarding isn't done.
        val dest = resolveStartDestination(
            isOnboardingComplete = { false },
            countNonEndedMedications = { 5 },
        )
        assertEquals(NavRoute.Onboarding.route, dest)
    }

    @Test
    fun routesToMedicationList_whenCompleteAndZeroMeds() = runTest {
        val dest = resolveStartDestination(
            isOnboardingComplete = { true },
            countNonEndedMedications = { 0 },
        )
        assertEquals(NavRoute.MedicationList.route, dest)
    }

    @Test
    fun routesToToday_whenCompleteAndHasMedications() = runTest {
        val dest = resolveStartDestination(
            isOnboardingComplete = { true },
            countNonEndedMedications = { 1 },
        )
        assertEquals(NavRoute.Today.route, dest)
    }

    @Test
    fun routesToToday_whenCompleteAndManyMedications() = runTest {
        val dest = resolveStartDestination(
            isOnboardingComplete = { true },
            countNonEndedMedications = { 10 },
        )
        assertEquals(NavRoute.Today.route, dest)
    }
}
