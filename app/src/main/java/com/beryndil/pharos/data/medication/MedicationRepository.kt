package com.beryndil.pharos.data.medication

import com.beryndil.pharos.data.drugref.dao.IngredientDao
import com.beryndil.pharos.data.drugref.dao.ProductDao
import com.beryndil.pharos.data.regimen.dao.MedicationDao
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
    private val productDao: ProductDao,
    private val ingredientDao: IngredientDao,
) {

    // ── Drug reference search ─────────────────────────────────────────────

    /**
     * Search the local RxNorm fixture by product name (contains, case-insensitive).
     * Returns up to 30 results. Resolves ingredient names for each product.
     *
     * Returns empty list if [query] is fewer than 2 characters.
     */
    suspend fun searchDrugs(query: String): List<DrugSearchResult> {
        if (query.length < 2) return emptyList()
        // The drug-reference DB is non-critical public data (spec §2.11). If it is unavailable or
        // unreadable, drug search degrades to "no matches" so the user falls back to free-text
        // entry — it must never crash the add-medication flow.
        return try {
            val products = productDao.searchByName(query)
            if (products.isEmpty()) return emptyList()

            // Collect all unique ingredient RxCUIs across all results, batch-fetch names once.
            val allRxcuis = products
                .flatMap { parseIngredientRxcuis(it.ingredientsJson) }
                .distinct()
            val ingredientMap = ingredientDao.getByRxcuiList(allRxcuis)
                .associateBy { it.rxcui }

            products.map { product ->
                val rxcuis = parseIngredientRxcuis(product.ingredientsJson)
                DrugSearchResult(
                    rxcui = product.rxcui,
                    name = product.name,
                    strength = product.strength,
                    rxNormForm = product.form,
                    ingredientRxcuis = rxcuis,
                    ingredientNames = rxcuis.mapNotNull { ingredientMap[it]?.name },
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
     * @param newIngredientRxcuis Active ingredient RxCUIs of the medication being added/edited.
     * @param excludeMedId ID of the medication being *edited* — excluded from comparison so a
     *   med doesn't flag itself. Pass null when adding a new medication.
     */
    suspend fun checkDuplicateIngredients(
        newIngredientRxcuis: List<String>,
        excludeMedId: String? = null,
    ): List<DuplicateWarning> {
        if (newIngredientRxcuis.isEmpty()) return emptyList()

        val activeMeds = medicationDao.getActiveOnce()
        val newSet = newIngredientRxcuis.toSet()

        val warnings = mutableListOf<DuplicateWarning>()

        for (med in activeMeds) {
            if (med.id == excludeMedId) continue
            val existingRxcuis = parseIngredientRxcuis(med.ingredientsJson)
            val shared = existingRxcuis.filter { it in newSet }
            if (shared.isEmpty()) continue

            // Resolve ingredient names for display. If the drug-ref DB is unavailable, the
            // duplicate comparison above still holds (it compares RxCUIs, not names) — we just
            // show the RxCUI instead of the resolved name. The safety warning is never suppressed.
            val ingredientMap = safeIngredientNames(shared)
            for (rxcui in shared) {
                val ingredientName = ingredientMap[rxcui] ?: rxcui
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
     * Batch-resolve ingredient RxCUIs to names from the drug-reference DB, failing soft.
     * Returns rxcui -> name. On any drug-ref DB error returns an empty map so callers fall back
     * to showing the RxCUI; a reference-DB problem never throws into a calling flow.
     */
    private suspend fun safeIngredientNames(rxcuis: List<String>): Map<String, String> =
        try {
            ingredientDao.getByRxcuiList(rxcuis).associate { it.rxcui to it.name }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Ingredient name lookup failed; using RxCUIs. ${e.javaClass.simpleName}")
            emptyMap()
        }

    // ── Regimen CRUD ─────────────────────────────────────────────────────

    /** Observe all non-ended medications, ordered by name. Emits on every change. */
    fun observeActiveMedications(): Flow<List<MedicationEntity>> =
        medicationDao.observeActive()

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

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Encode a list of RxCUI strings to the JSON format used in [MedicationEntity.ingredientsJson]
     * and [com.beryndil.pharos.data.drugref.entity.ProductEntity.ingredientsJson].
     * Example output: `["161","5640"]`
     */
    fun encodeIngredientsJson(rxcuis: List<String>): String =
        Json.encodeToString(rxcuis)

    /**
     * Parse a JSON array of RxCUI strings.
     * Returns an empty list on any parse error (malformed data is treated as no ingredients).
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
