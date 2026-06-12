package com.beryndil.pharos.data.drugref

import android.util.Log
import com.beryndil.pharos.BuildConfig
import com.beryndil.pharos.data.drugref.dao.LabelCacheDao
import com.beryndil.pharos.data.drugref.entity.LabelCacheEntity

/**
 * Cache-aside layer for drug label reference data (spec §2.10, §3.2, Law 9).
 *
 * Cache strategy: check [LabelCacheDao] first. On a cache miss, call [DrugLabelService].
 * On a successful network response, persist to the cache **forever** (never expire — the spec
 * says "cached locally forever after [first fetch]"). The cached entry is the source of truth
 * for all subsequent reads, including while offline.
 *
 * Thread-safety: all public methods must be called on [kotlinx.coroutines.Dispatchers.IO].
 * The repository itself is stateless; [LabelCacheDao] is thread-safe through Room.
 */
class DrugLabelRepository(
    private val labelCacheDao: LabelCacheDao,
    private val drugLabelService: DrugLabelService,
) {

    /**
     * Returns the cached label for [productRxcui], fetching and caching from the network if
     * not already available.
     *
     * @return [LabelCacheEntity] if cached (possibly after a fresh network fetch); null if
     * not cached AND the network request failed (offline or no data for this RxCUI).
     */
    suspend fun getOrFetchLabel(productRxcui: String): LabelCacheEntity? {
        // Check cache first (always — never re-fetch an already-cached label).
        val cached = labelCacheDao.getByProduct(productRxcui)
        if (cached != null) return cached

        // Cache miss: attempt network fetch.
        val fetched = try {
            drugLabelService.fetchLabel(productRxcui)
        } catch (e: Exception) {
            Log.w(TAG, "Label fetch failed unexpectedly: ${e.javaClass.simpleName}")
            null
        }

        if (fetched != null) {
            val entity = LabelCacheEntity(
                productRxcui = productRxcui,
                sideEffectsText = fetched.sideEffectsText,
                interactionsText = fetched.interactionsText,
                source = fetched.source,
                fetchedAtEpochMs = System.currentTimeMillis(),
            )
            labelCacheDao.upsert(entity)
            return entity
        }

        return null
    }

    /**
     * Returns the cached label if it exists, WITHOUT attempting a network fetch.
     * Use this when the caller wants to display cached data only (e.g., offline read path).
     */
    suspend fun getCachedLabel(productRxcui: String): LabelCacheEntity? =
        labelCacheDao.getByProduct(productRxcui)

    /**
     * Fire-and-forget: ensures the label for [productRxcui] is in the cache.
     * Silently drops any network error — the cache will be populated on the next
     * successful call. Returns immediately if already cached (no-op).
     *
     * Must be called from an IO coroutine (typically launched from a ViewModel scope).
     */
    suspend fun ensureLabelCached(productRxcui: String) {
        if (labelCacheDao.getByProduct(productRxcui) != null) return
        try {
            getOrFetchLabel(productRxcui)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "ensureLabelCached: fetch error for $productRxcui: ${e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        private const val TAG = "DrugLabelRepository"
    }
}
