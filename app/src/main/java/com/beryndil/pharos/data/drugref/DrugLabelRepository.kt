package com.beryndil.pharos.data.drugref

import android.util.Log
import com.beryndil.pharos.BuildConfig
import com.beryndil.pharos.core.debug.DebugLogger
import com.beryndil.pharos.data.drugref.dao.LabelCacheDao
import com.beryndil.pharos.data.drugref.entity.LabelCacheEntity

/**
 * Cache-aside layer for drug label reference data (spec §2.10, §3.2, Law 9).
 *
 * Cache strategy: check [LabelCacheDao] first. On a cache miss, call [DrugLabelService].
 * On a successful network response, persist to the cache. Call [invalidateAndRefetch] to
 * force a fresh network fetch (user-triggered refresh).
 *
 * Thread-safety: all public methods must be called on [kotlinx.coroutines.Dispatchers.IO].
 */
class DrugLabelRepository(
    private val labelCacheDao: LabelCacheDao,
    private val drugLabelService: DrugLabelService,
) {

    /**
     * Returns the cached label for [productRxcui], fetching from the network if not cached.
     *
     * @return [LabelCacheEntity] if cached or freshly fetched; null if not cached AND the
     * network request failed (offline or no data for this drug).
     */
    suspend fun getOrFetchLabel(productRxcui: String, medicationName: String? = null): LabelCacheEntity? {
        val cached = labelCacheDao.getByProduct(productRxcui)
        if (cached != null) {
            DebugLogger.log("LabelRepo", "cache HIT for $productRxcui")
            return cached
        }
        DebugLogger.log("LabelRepo", "cache MISS for $productRxcui — fetching network")
        return fetchAndCache(productRxcui, medicationName)
    }

    /**
     * Returns the cached label if it exists, WITHOUT attempting a network fetch.
     */
    suspend fun getCachedLabel(productRxcui: String): LabelCacheEntity? =
        labelCacheDao.getByProduct(productRxcui)

    /**
     * Deletes the cached entry and re-fetches from the network. Used by the refresh action
     * on the drug reference screen.
     */
    suspend fun invalidateAndRefetch(productRxcui: String, medicationName: String? = null): LabelCacheEntity? {
        labelCacheDao.deleteByProduct(productRxcui)
        return fetchAndCache(productRxcui, medicationName)
    }

    /**
     * Fire-and-forget: ensures the label for [productRxcui] is in the cache.
     * Silently drops any network error. No-op if already cached.
     */
    suspend fun ensureLabelCached(productRxcui: String, medicationName: String? = null) {
        if (labelCacheDao.getByProduct(productRxcui) != null) return
        try {
            fetchAndCache(productRxcui, medicationName)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "ensureLabelCached: fetch error for $productRxcui: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun fetchAndCache(productRxcui: String, medicationName: String?): LabelCacheEntity? {
        val fetched = try {
            drugLabelService.fetchLabel(productRxcui, medicationName)
        } catch (e: Exception) {
            Log.w(TAG, "Label fetch failed unexpectedly: ${e.javaClass.simpleName}")
            DebugLogger.logError("LabelRepo", "fetchLabel threw for $productRxcui", e)
            null
        }
        if (fetched != null) {
            DebugLogger.log("LabelRepo", "fetched label for $productRxcui from ${fetched.source}")
        } else {
            DebugLogger.log("LabelRepo", "fetch returned null for $productRxcui (offline or no data)")
        }
        if (fetched != null) {
            val entity = LabelCacheEntity(
                productRxcui = productRxcui,
                sideEffectsText = fetched.sideEffectsText,
                interactionsText = fetched.interactionsText,
                warningsText = fetched.warningsText,
                precautionsText = fetched.precautionsText,
                contraindicationsText = fetched.contraindicationsText,
                boxedWarningText = fetched.boxedWarningText,
                foodEffectText = fetched.foodEffectText,
                source = fetched.source,
                fetchedAtEpochMs = System.currentTimeMillis(),
            )
            try {
                labelCacheDao.upsert(entity)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Label cache upsert failed: ${e.javaClass.simpleName}")
            }
            return entity
        }
        return null
    }

    private companion object {
        private const val TAG = "DrugLabelRepository"
    }
}
