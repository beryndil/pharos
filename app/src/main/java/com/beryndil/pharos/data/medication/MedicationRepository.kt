package com.beryndil.pharos.data.medication

import com.beryndil.pharos.data.drugref.dao.DrugSearchDao
import com.beryndil.pharos.data.drugref.dao.IngredientMapDao
import com.beryndil.pharos.data.regimen.dao.DoseInstanceDao
import com.beryndil.pharos.data.regimen.dao.DoseTransitionDao
import com.beryndil.pharos.data.regimen.dao.MedicationDao
import com.beryndil.pharos.data.regimen.dao.RefillRecordDao
import com.beryndil.pharos.data.regimen.dao.ScheduleDao
import com.beryndil.pharos.data.regimen.dao.SchedulePhaseDao
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import com.beryndil.pharos.data.regimen.entity.MedicationStatus
import com.beryndil.pharos.medication.model.DrugSearchResult
import com.beryndil.pharos.medication.model.DuplicateWarning
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repository for medication identity and entry (Slice 2).
 *
 * Bridges the regimen database (read/write PHI) and the drug-reference database (read-only
 * public data) to serve the Add/Edit medication flow.
 *
 * All suspend functions must be called from a [kotlinx.coroutines.Dispatchers.IO] coroutine.
 * Room's own dispatcher handles DAO calls; business logic is kept lightweight.
 */
class MedicationRepository(
    private val medicationDao: MedicationDao,
    private val drugSearchDao: DrugSearchDao,
    private val ingredientMapDao: IngredientMapDao,
    private val doseTransitionDao: DoseTransitionDao,
    private val doseInstanceDao: DoseInstanceDao,
    private val schedulePhaseDao: SchedulePhaseDao,
    private val scheduleDao: ScheduleDao,
    private val refillRecordDao: RefillRecordDao,
) {

    // ── Drug reference search ─────────────────────────────────────────────

    /**
     * Search the local RxNorm asset by drug name (prefix-first, then contains).
     * Returns up to 30 results. Resolves ingredient names for each result.
     *
     * Returns empty list if [query] is fewer than 2 characters.
     *
     * SAFETY: the drug-reference DB is non-critical public data (spec §2.11). If it is
     * unavailable or unreadable, search degrades to "no matches" so the user falls back to
     * free-text entry — it must NEVER crash the add-medication flow.
     */
    suspend fun searchDrugs(query: String): List<DrugSearchResult> {
        if (query.length < 2) return emptyList()
        return try {
            val q = query.lowercase().trim()
            val results = drugSearchDao.searchByName(q)
            if (results.isEmpty()) return emptyList()

            // Batch-fetch all ingredient edges for all results in one query (avoids N+1).
            val allDrugRxcuis = results.map { it.rxcui }.distinct()
            val allEdges = ingredientMapDao.getForDrugs(allDrugRxcuis)
            val edgesByDrug = allEdges.groupBy { it.drugRxcui }

            results.map { drug ->
                val edges = edgesByDrug[drug.rxcui] ?: emptyList()
                DrugSearchResult(
                    rxcui = drug.rxcui,
                    name = drug.name,
                    tty = drug.tty,
                    ingredientRxcuis = edges.map { it.ingredientRxcui },
                    ingredientNames = edges.map { it.ingredientName },
                )
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Drug search failed; degrading to free-text. ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    // ── Duplicate-ingredient detection ───────────────────────────────────

    /**
     * Compares [newIngredientRxcuis] against all ACTIVE medications in the regimen.
     *
     * Returns a [DuplicateWarning] for each (existingMed, sharedIngredient) pair found.
     * An empty list means no duplicates.
     *
     * SAFETY: RxCUI-set intersection is the source of truth. If the drug-ref DB is unavailable,
     * the comparison still holds (RxCUIs are compared directly) — we simply show the RxCUI
     * as the ingredient name rather than the resolved name.
     *
     * The warning is NEVER suppressed between non-linked medications. It IS suppressed between
     * two medications that are explicitly linked as substitutes (V1.3-F2): the user takes one
     * OR the other, so sharing an ingredient is expected and not a safety concern. All other
     * pairings continue to fire normally — safety detection is never weakened for unlinked meds.
     *
     * @param newIngredientRxcuis Active ingredient RxCUIs of the medication being added/edited.
     * @param excludeMedId ID of the medication being *edited* — excluded from comparison so a
     *   med doesn't flag itself. Pass null when adding a new medication.
     */
    suspend fun checkDuplicateIngredients(
        newIngredientRxcuis: List<String>,
        excludeMedId: String? = null,
        combinedWithMedId: String? = null,
    ): List<DuplicateWarning> {
        if (newIngredientRxcuis.isEmpty()) return emptyList()

        val activeMeds = medicationDao.getActiveOnce()
        val newSet = newIngredientRxcuis.toSet()

        val warnings = mutableListOf<DuplicateWarning>()

        for (med in activeMeds) {
            if (med.id == excludeMedId) continue
            // Skip the combined-pair partner — user declared these as an intentional split prescription.
            if (combinedWithMedId != null && med.id == combinedWithMedId) continue

            val existingRxcuis = parseIngredientRxcuis(med.ingredientsJson)
            val shared = existingRxcuis.filter { it in newSet }
            if (shared.isEmpty()) continue

            // Resolve ingredient names for display. Falls back to the RxCUI string if unavailable.
            val nameMap = safeIngredientNames(shared)
            for (rxcui in shared) {
                val ingredientName = nameMap[rxcui] ?: rxcui
                warnings += DuplicateWarning(
                    existingMedName = med.name,
                    ingredientName = ingredientName,
                )
            }
        }
        return warnings
    }

    /**
     * Resolve human-readable names for a list of ingredient RxCUI strings.
     * Unresolved RxCUIs (not in the local DB) are returned as-is so no data is silently dropped.
     */
    suspend fun getIngredientNames(rxcuis: List<String>): List<String> {
        if (rxcuis.isEmpty()) return emptyList()
        val nameMap = safeIngredientNames(rxcuis)
        return rxcuis.map { rxcui -> nameMap[rxcui] ?: rxcui }
    }

    /**
     * Batch-resolve ingredient RxCUIs to names from `ingredient_map`, failing soft.
     * Returns rxcui -> name. On any drug-ref DB error returns an empty map so callers fall back
     * to showing the RxCUI; a reference-DB problem never throws into a calling flow.
     */
    private suspend fun safeIngredientNames(rxcuis: List<String>): Map<String, String> =
        try {
            ingredientMapDao.getByIngredientRxcuis(rxcuis)
                .groupBy { it.ingredientRxcui }
                .mapValues { (_, entries) -> entries.first().ingredientName }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Ingredient name lookup failed; using RxCUIs. ${e.javaClass.simpleName}")
            emptyMap()
        }

    // ── Regimen CRUD ─────────────────────────────────────────────────────

    /** Observe all non-ended medications, ordered by name. Emits on every change. */
    fun observeActiveMedications(): Flow<List<MedicationEntity>> =
        medicationDao.observeActive()

    /** Observe ALL medications regardless of status, ordered by name. Emits on every change. */
    fun observeAllMedications(): Flow<List<MedicationEntity>> =
        medicationDao.observeAll()

    /** Insert a new medication. */
    suspend fun saveMedication(entity: MedicationEntity) {
        medicationDao.insert(entity)
    }

    /** Update an existing medication row. */
    suspend fun updateMedication(entity: MedicationEntity) {
        medicationDao.update(entity)
    }

    /** Fetch a medication by ID. Returns null if not found. */
    suspend fun getMedication(id: String): MedicationEntity? =
        medicationDao.getById(id)

    /**
     * Returns active medications with [MedicationEntity.isCritical] = true, ordered by name.
     * Used by the add/edit VM to check whether this is the user's first critical med (to
     * trigger the lazy DND-access permission request) and by the reliability dashboard.
     */
    suspend fun getCriticalActiveMedications(): List<MedicationEntity> =
        medicationDao.getCriticalActive()

    /** Pause a medication (status → PAUSED). No-op if not found. */
    suspend fun pauseMedication(medId: String) {
        val med = medicationDao.getById(medId) ?: return
        medicationDao.update(
            med.copy(
                status = MedicationStatus.PAUSED.name,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Resume a paused medication (status → ACTIVE). No-op if not found. */
    suspend fun resumeMedication(medId: String) {
        val med = medicationDao.getById(medId) ?: return
        medicationDao.update(
            med.copy(
                status = MedicationStatus.ACTIVE.name,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    /** End a medication (status → ENDED). No-op if not found. */
    suspend fun endMedication(medId: String) {
        val med = medicationDao.getById(medId) ?: return
        medicationDao.update(
            med.copy(
                status = MedicationStatus.ENDED.name,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Permanently delete an ENDED medication and all its associated records.
     *
     * Deletion order respects FK RESTRICT constraints:
     *  1. dose_transitions (references dose_instances + medications)
     *  2. dose_instances (references medications + schedules)
     *  3. schedule_phases (references schedules)
     *  4. schedules (references medications)
     *  5. refill_records (references medications)
     *  6. Clear substituteForMedId cross-references on other medications
     *  7. medications row
     *
     * No-op if the medication is not found or is not in ENDED status (guard
     * against accidental deletion of active medications).
     */
    suspend fun deleteMedication(medId: String) {
        val med = medicationDao.getById(medId) ?: return
        if (med.status != MedicationStatus.ENDED.name) return
        doseTransitionDao.deleteByMedication(medId)
        doseInstanceDao.deleteByMedication(medId)
        schedulePhaseDao.deleteByMedication(medId)
        scheduleDao.deleteByMedication(medId)
        refillRecordDao.deleteByMedication(medId)
        medicationDao.clearSubstituteRef(medId)
        medicationDao.deleteById(medId)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Encode a list of RxCUI strings to the JSON format used in [MedicationEntity.ingredientsJson].
     * Example output: `["161","5640"]`
     */
    fun encodeIngredientsJson(rxcuis: List<String>): String =
        Json.encodeToString(rxcuis)

    /**
     * Parse a JSON array of RxCUI strings from [MedicationEntity.ingredientsJson].
     * Returns an empty list on any parse error (malformed data treated as no ingredients).
     */
    fun parseIngredientRxcuis(json: String): List<String> =
        try {
            Json.decodeFromString<List<String>>(json)
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Map a RxNorm dosage form string to the closest [MedicationForm] enum value.
     * Falls through to [MedicationForm.OTHER] for unrecognised strings.
     * Retained for potential future use (e.g., TTY-based form inference from drug names).
     */
    fun mapRxNormForm(rxNormForm: String): MedicationForm = when {
        rxNormForm.contains("tablet", ignoreCase = true) -> MedicationForm.TABLET
        rxNormForm.contains("capsule", ignoreCase = true) -> MedicationForm.CAPSULE
        rxNormForm.contains("solution", ignoreCase = true) ||
            rxNormForm.contains("liquid", ignoreCase = true) ||
            rxNormForm.contains("syrup", ignoreCase = true) ||
            rxNormForm.contains("suspension", ignoreCase = true) -> MedicationForm.LIQUID
        rxNormForm.contains("injection", ignoreCase = true) ||
            rxNormForm.contains("injectable", ignoreCase = true) ||
            rxNormForm.contains("infusion", ignoreCase = true) -> MedicationForm.INJECTION
        rxNormForm.contains("inhaler", ignoreCase = true) ||
            rxNormForm.contains("aerosol", ignoreCase = true) ||
            rxNormForm.contains("inhalation", ignoreCase = true) -> MedicationForm.INHALER
        rxNormForm.contains("patch", ignoreCase = true) ||
            rxNormForm.contains("transdermal", ignoreCase = true) -> MedicationForm.PATCH
        rxNormForm.contains("drop", ignoreCase = true) ||
            rxNormForm.contains("ophthalmic", ignoreCase = true) -> MedicationForm.DROPS
        rxNormForm.contains("cream", ignoreCase = true) ||
            rxNormForm.contains("ointment", ignoreCase = true) ||
            rxNormForm.contains("topical", ignoreCase = true) -> MedicationForm.CREAM
        else -> MedicationForm.OTHER
    }

    private companion object {
        const val TAG = "MedicationRepository"
    }
}
