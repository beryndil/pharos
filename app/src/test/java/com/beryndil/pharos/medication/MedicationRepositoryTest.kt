package com.beryndil.pharos.medication

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.data.drugref.DrugRefDatabase
import com.beryndil.pharos.data.drugref.entity.DrugSearchEntity
import com.beryndil.pharos.data.drugref.entity.IngredientMapEntity
import com.beryndil.pharos.data.medication.MedicationRepository
import com.beryndil.pharos.data.regimen.RegimenDatabase
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Unit tests for [MedicationRepository] (Slice 2, updated for v2 DrugRef schema):
 *  - Duplicate-ingredient detection (positive + negative, multi-ingredient combo) — SAFETY-CRITICAL
 *  - RxNorm local resolution (searchDrugs returns expected matches)
 *  - Free-text fallback (persists an unresolved med flagged correctly)
 *  - Repository round-trips a med through the DB
 *  - getIngredientNames resolves correctly
 */
@RunWith(RobolectricTestRunner::class)
class MedicationRepositoryTest {

    private lateinit var regimenDb: RegimenDatabase
    private lateinit var drugRefDb: DrugRefDatabase
    private lateinit var repo: MedicationRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        regimenDb = Room.inMemoryDatabaseBuilder(ctx, RegimenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        drugRefDb = Room.inMemoryDatabaseBuilder(ctx, DrugRefDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = MedicationRepository(
            medicationDao = regimenDb.medicationDao(),
            drugSearchDao = drugRefDb.drugSearchDao(),
            ingredientMapDao = drugRefDb.ingredientMapDao(),
            doseTransitionDao = regimenDb.doseTransitionDao(),
            doseInstanceDao = regimenDb.doseInstanceDao(),
            schedulePhaseDao = regimenDb.schedulePhaseDao(),
            scheduleDao = regimenDb.scheduleDao(),
            refillRecordDao = regimenDb.refillRecordDao(),
        )

        // Seed fixture data used across tests.
        runTest { seedDrugRefFixture() }
    }

    @After
    fun tearDown() {
        regimenDb.close()
        drugRefDb.close()
    }

    // ── Graceful degradation (drug-ref DB unavailable must not crash) ──────

    @Test
    fun searchDrugs_returnsEmptyWhenDrugRefDbUnavailable() = runTest {
        drugRefDb.close()
        val results = repo.searchDrugs("acetaminophen")
        assertTrue("searchDrugs must degrade to empty, not throw", results.isEmpty())
    }

    @Test
    fun getIngredientNames_fallsBackToRxcuiWhenDrugRefDbUnavailable() = runTest {
        drugRefDb.close()
        val names = repo.getIngredientNames(listOf("161", "5640"))
        assertEquals(listOf("161", "5640"), names)
    }

    // ── RxNorm local resolution ───────────────────────────────────────────

    @Test
    fun searchDrugs_returnsMatchForKnownDrugName() = runTest {
        val results = repo.searchDrugs("Metoprolol")
        assertTrue("Expected matches for 'Metoprolol'", results.isNotEmpty())
        assertTrue(results.any { it.name.contains("Metoprolol", ignoreCase = true) })
    }

    @Test
    fun searchDrugs_belowMinLength_returnsEmpty() = runTest {
        val results = repo.searchDrugs("M")
        assertTrue("Single-char query must return empty list", results.isEmpty())
    }

    @Test
    fun searchDrugs_noMatch_returnsEmpty() = runTest {
        val results = repo.searchDrugs("ZZZnonexistent999")
        assertTrue("Unknown drug must return empty list", results.isEmpty())
    }

    @Test
    fun searchDrugs_populatesIngredientNames() = runTest {
        val results = repo.searchDrugs("Tylenol")
        assertTrue("Expected Tylenol match", results.isNotEmpty())
        val tylenol = results.first { it.name.contains("Tylenol", ignoreCase = true) }
        assertTrue(
            "Tylenol ingredient names must not be empty",
            tylenol.ingredientNames.isNotEmpty(),
        )
        assertTrue(
            "Tylenol ingredient name must contain Acetaminophen",
            tylenol.ingredientNames.any { it.contains("Acetaminophen", ignoreCase = true) },
        )
    }

    @Test
    fun searchDrugs_comboProduct_populatesBothIngredientNames() = runTest {
        val results = repo.searchDrugs("ComboTest")
        assertTrue("Expected ComboTest match", results.isNotEmpty())
        val combo = results.first()
        assertEquals("Combo product must have 2 ingredients", 2, combo.ingredientNames.size)
    }

    @Test
    fun searchDrugs_resultCarriesTty() = runTest {
        val results = repo.searchDrugs("Metoprolol")
        assertTrue("Expected Metoprolol results", results.isNotEmpty())
        results.forEach { result ->
            assertTrue("tty must be non-blank", result.tty.isNotBlank())
        }
    }

    // ── Free-text fallback ────────────────────────────────────────────────

    @Test
    fun saveMedication_freeText_persistsWithIsFreeTextTrue() = runTest {
        val med = sampleMedication(isFreeText = true, rxcui = null, ingredientsJson = "[]")
        repo.saveMedication(med)
        val retrieved = repo.getMedication(med.id)
        assertNotNull("Free-text med must be retrievable", retrieved)
        assertTrue("isFreeText must be true", retrieved!!.isFreeText)
        assertEquals("ingredientsJson must be empty array", "[]", retrieved.ingredientsJson)
    }

    @Test
    fun saveMedication_resolved_persistsWithIsFreeTextFalse() = runTest {
        val med = sampleMedication(isFreeText = false, rxcui = "866427", ingredientsJson = """["41493"]""")
        repo.saveMedication(med)
        val retrieved = repo.getMedication(med.id)
        assertNotNull(retrieved)
        assertFalse("isFreeText must be false for resolved med", retrieved!!.isFreeText)
        assertEquals("""["41493"]""", retrieved.ingredientsJson)
    }

    // ── Repository round-trip ─────────────────────────────────────────────

    @Test
    fun saveThenRetrieveMedication_roundTripsAllFields() = runTest {
        val med = sampleMedication()
        repo.saveMedication(med)
        val retrieved = repo.getMedication(med.id)
        assertEquals("Round-trip must preserve all fields", med, retrieved)
    }

    @Test
    fun updateMedication_persistsChanges() = runTest {
        val med = sampleMedication()
        repo.saveMedication(med)
        val updated = med.copy(strength = "50 mg", updatedAtEpochMs = med.updatedAtEpochMs + 1_000)
        repo.updateMedication(updated)
        val retrieved = repo.getMedication(med.id)
        assertEquals("Strength must be updated", "50 mg", retrieved?.strength)
    }

    // ── Duplicate-ingredient detection ── SAFETY-CRITICAL ────────────────

    @Test
    fun checkDuplicates_noExistingMeds_returnsEmpty() = runTest {
        val warnings = repo.checkDuplicateIngredients(newIngredientRxcuis = listOf("161"))
        assertTrue("No existing meds → no warnings", warnings.isEmpty())
    }

    @Test
    fun checkDuplicates_existingMedSameIngredient_returnsWarning() = runTest {
        // Save an active med with Acetaminophen (RxCUI "161").
        val existing = sampleMedication(ingredientsJson = """["161"]""")
        repo.saveMedication(existing)

        // Adding another med with the same ingredient — must fire the warning.
        val warnings = repo.checkDuplicateIngredients(newIngredientRxcuis = listOf("161"))
        assertEquals("Must detect one duplicate", 1, warnings.size)
        assertEquals(existing.name, warnings.first().existingMedName)
        assertTrue(
            "Ingredient name should contain Acetaminophen",
            warnings.first().ingredientName.contains("Acetaminophen", ignoreCase = true),
        )
    }

    @Test
    fun checkDuplicates_existingMedDifferentIngredient_returnsEmpty() = runTest {
        // Metoprolol Succinate has ingredient RxCUI "41493" (not "161").
        val existing = sampleMedication(ingredientsJson = """["41493"]""")
        repo.saveMedication(existing)

        // New med has Acetaminophen — no overlap.
        val warnings = repo.checkDuplicateIngredients(newIngredientRxcuis = listOf("161"))
        assertTrue("Different ingredients → no warnings", warnings.isEmpty())
    }

    @Test
    fun checkDuplicates_comboProductOverlap_detectsBothIngredients() = runTest {
        // Two existing meds each with one ingredient of the combo product.
        val med1 = sampleMedication(id = UUID.randomUUID().toString(), name = "Med1", ingredientsJson = """["161"]""")
        val med2 = sampleMedication(id = UUID.randomUUID().toString(), name = "Med2", ingredientsJson = """["5640"]""")
        repo.saveMedication(med1)
        repo.saveMedication(med2)

        // Combo product contains both ingredients.
        val warnings = repo.checkDuplicateIngredients(
            newIngredientRxcuis = listOf("161", "5640"),
        )
        assertEquals("Combo product must generate 2 warnings", 2, warnings.size)
        assertTrue(warnings.any { it.existingMedName == "Med1" })
        assertTrue(warnings.any { it.existingMedName == "Med2" })
    }

    @Test
    fun checkDuplicates_excludesMedBeingEdited() = runTest {
        // A med that is being edited — must not duplicate-flag itself.
        val med = sampleMedication(ingredientsJson = """["161"]""")
        repo.saveMedication(med)

        val warnings = repo.checkDuplicateIngredients(
            newIngredientRxcuis = listOf("161"),
            excludeMedId = med.id,
        )
        assertTrue("Med being edited must be excluded from duplicate check", warnings.isEmpty())
    }

    @Test
    fun checkDuplicates_freeTextIngredients_returnsEmpty() = runTest {
        val existing = sampleMedication(ingredientsJson = """["161"]""")
        repo.saveMedication(existing)

        // Free-text med has no ingredients.
        val warnings = repo.checkDuplicateIngredients(newIngredientRxcuis = emptyList())
        assertTrue("Empty ingredient list → no warnings", warnings.isEmpty())
    }

    // ── Combined-prescription suppression — SAFETY-CRITICAL ─────────────────

    /**
     * When the medication being added/edited declares another med as its combined-prescription
     * partner (an intentional split prescription, e.g. 60 mg + 30 mg = 90 mg/day), no
     * duplicate-ingredient warning fires between them even when they share an ingredient.
     * All other meds are unaffected.
     */
    @Test
    fun checkDuplicates_combinedLink_suppressesWarningBetweenLinkedPair() = runTest {
        // B: the partner medication (Acetaminophen ingredient).
        val medB = sampleMedication(id = "b1", name = "MedB", ingredientsJson = """["161"]""")
        repo.saveMedication(medB)

        // The new med declares B as its combined partner — no warning expected even though
        // they share ingredient 161.
        val warnings = repo.checkDuplicateIngredients(
            newIngredientRxcuis = listOf("161"),
            excludeMedId = null,
            combinedWithMedId = medB.id,
        )
        assertTrue(
            "Warning must be suppressed between a med and its declared combined partner",
            warnings.isEmpty(),
        )
    }

    /**
     * Editing direction: the med being edited (A) declares B as its combined partner.
     * No duplicate-ingredient warning fires between A and B.
     */
    @Test
    fun checkDuplicates_combinedLink_suppressesWarningWhenEditing() = runTest {
        // A (being edited) — passed as excludeMedId so it does not flag itself.
        val medAId = "a1"
        val medB = sampleMedication(
            id = "b2",
            name = "MedB",
            ingredientsJson = """["161"]""",
        )
        repo.saveMedication(medB)

        // Editing A and declaring B as its combined partner triggers suppression.
        val warnings = repo.checkDuplicateIngredients(
            newIngredientRxcuis = listOf("161"),
            excludeMedId = medAId,
            combinedWithMedId = medB.id,
        )
        assertTrue(
            "Warning must be suppressed when the existing med is the declared combined partner",
            warnings.isEmpty(),
        )
    }

    /**
     * A third unlinked med sharing an ingredient with a combined pair still fires the warning.
     * Suppression ONLY applies to the explicitly declared combined partner; all other pairings
     * are unaffected — safety detection is never weakened for unlinked medications.
     */
    @Test
    fun checkDuplicates_combinedLink_unlinkedThirdMedStillFiresWarning() = runTest {
        // B: the declared combined partner.
        val medB = sampleMedication(id = "b3", name = "MedB", ingredientsJson = """["161"]""")
        repo.saveMedication(medB)
        // C: an unrelated med also containing Acetaminophen (not the combined partner).
        val medC = sampleMedication(id = "c3", name = "MedC", ingredientsJson = """["161"]""")
        repo.saveMedication(medC)

        // Combined partner is B — warning vs B suppressed, but warning vs C must still fire.
        val warnings = repo.checkDuplicateIngredients(
            newIngredientRxcuis = listOf("161"),
            excludeMedId = null,
            combinedWithMedId = medB.id,
        )
        assertEquals("Warning must still fire for the unlinked third med", 1, warnings.size)
        assertEquals("MedC", warnings.first().existingMedName)
    }

    /**
     * Duplicate detection must fire even when the drug-ref DB has no name for the shared
     * ingredient (e.g., an edge-case RxCUI not in the bundled subset). The RxCUI itself is
     * shown in the warning instead of a resolved name — the warning is NEVER suppressed.
     */
    @Test
    fun checkDuplicates_unknownIngredientRxcui_stillFiresWarningWithRxcuiAsName() = runTest {
        val obscureRxcui = "UNKNOWN_99999"
        val existing = sampleMedication(ingredientsJson = """["$obscureRxcui"]""")
        repo.saveMedication(existing)

        val warnings = repo.checkDuplicateIngredients(
            newIngredientRxcuis = listOf(obscureRxcui),
        )
        assertEquals("Warning must fire even for unknown RxCUI", 1, warnings.size)
        assertEquals(
            "Warning ingredient name must be the RxCUI when no name is available",
            obscureRxcui,
            warnings.first().ingredientName,
        )
    }

    // ── Ingredient name resolution ────────────────────────────────────────

    @Test
    fun getIngredientNames_resolvesKnownRxcui() = runTest {
        val names = repo.getIngredientNames(listOf("161"))
        assertEquals(1, names.size)
        assertTrue(names.first().contains("Acetaminophen", ignoreCase = true))
    }

    @Test
    fun getIngredientNames_unknownRxcui_returnsRxcuiAsPlaceholder() = runTest {
        val names = repo.getIngredientNames(listOf("UNKNOWN_RXCUI"))
        assertEquals(1, names.size)
        assertEquals("UNKNOWN_RXCUI", names.first())
    }

    // ── JSON helpers ─────────────────────────────────────────────────────

    @Test
    fun encodeAndParseIngredientsJson_roundTrips() {
        val rxcuis = listOf("161", "5640", "41493")
        val json = repo.encodeIngredientsJson(rxcuis)
        val parsed = repo.parseIngredientRxcuis(json)
        assertEquals(rxcuis, parsed)
    }

    @Test
    fun parseIngredientRxcuis_malformedJson_returnsEmpty() {
        val parsed = repo.parseIngredientRxcuis("not-valid-json!!")
        assertTrue("Malformed JSON must return empty list", parsed.isEmpty())
    }

    @Test
    fun parseIngredientRxcuis_emptyArray_returnsEmpty() {
        val parsed = repo.parseIngredientRxcuis("[]")
        assertTrue(parsed.isEmpty())
    }

    // ── mapRxNormForm ─────────────────────────────────────────────────────

    @Test
    fun mapRxNormForm_tablet_returnsTablet() {
        assertEquals(MedicationForm.TABLET, repo.mapRxNormForm("Oral Tablet"))
    }

    @Test
    fun mapRxNormForm_capsule_returnsCapsule() {
        assertEquals(MedicationForm.CAPSULE, repo.mapRxNormForm("Oral Capsule"))
    }

    @Test
    fun mapRxNormForm_solution_returnsLiquid() {
        assertEquals(MedicationForm.LIQUID, repo.mapRxNormForm("Oral Solution"))
    }

    @Test
    fun mapRxNormForm_unknown_returnsOther() {
        assertEquals(MedicationForm.OTHER, repo.mapRxNormForm("Suppository"))
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Seeds [drugRefDb] with drug_search and ingredient_map rows matching the v2 schema.
     * Mirrors the data for known test drugs so search and name-resolution assertions work.
     */
    private suspend fun seedDrugRefFixture() {
        val drugs = listOf(
            DrugSearchEntity(rxcui = "161", name = "Acetaminophen", nameLower = "acetaminophen", tty = "IN"),
            DrugSearchEntity(rxcui = "41493", name = "Metoprolol Succinate", nameLower = "metoprolol succinate", tty = "PIN"),
            DrugSearchEntity(rxcui = "5640", name = "Ibuprofen", nameLower = "ibuprofen", tty = "IN"),
            DrugSearchEntity(rxcui = "209387", name = "Tylenol 500 MG Oral Tablet", nameLower = "tylenol 500 mg oral tablet", tty = "BN"),
            DrugSearchEntity(rxcui = "866427", name = "Metoprolol Succinate 25 MG Oral Tablet", nameLower = "metoprolol succinate 25 mg oral tablet", tty = "SCD"),
            DrugSearchEntity(rxcui = "999001", name = "ComboTest 200 MG Oral Tablet", nameLower = "combotest 200 mg oral tablet", tty = "SCD"),
        )
        val edges = listOf(
            // Tylenol → Acetaminophen
            IngredientMapEntity(drugRxcui = "209387", ingredientRxcui = "161", ingredientName = "Acetaminophen"),
            // Metoprolol Succinate tablet → Metoprolol Succinate ingredient
            IngredientMapEntity(drugRxcui = "866427", ingredientRxcui = "41493", ingredientName = "Metoprolol Succinate"),
            // ComboTest → Acetaminophen + Ibuprofen
            IngredientMapEntity(drugRxcui = "999001", ingredientRxcui = "161", ingredientName = "Acetaminophen"),
            IngredientMapEntity(drugRxcui = "999001", ingredientRxcui = "5640", ingredientName = "Ibuprofen"),
        )
        drugRefDb.drugSearchDao().insertAll(drugs)
        drugRefDb.ingredientMapDao().insertAll(edges)
    }

    private fun sampleMedication(
        id: String = UUID.randomUUID().toString(),
        name: String = "Test Medication",
        rxcui: String? = "209387",
        isFreeText: Boolean = false,
        ingredientsJson: String = """["161"]""",
        substituteForMedId: String? = null,
    ) = MedicationEntity(
        id = id,
        name = name,
        rxcui = rxcui,
        ingredientsJson = ingredientsJson,
        strength = "500 mg",
        form = MedicationForm.TABLET.name,
        doseAmount = "1 tablet",
        prescriber = null,
        pharmacy = null,
        substituteForMedId = substituteForMedId,
        purpose = null,
        isFreeText = isFreeText,
        status = MedicationStatus.ACTIVE.name,
        startEpochMs = 1_700_000_000_000L,
        endEpochMs = null,
        createdAtEpochMs = 1_700_000_000_000L,
        updatedAtEpochMs = 1_700_000_000_000L,
    )
}
