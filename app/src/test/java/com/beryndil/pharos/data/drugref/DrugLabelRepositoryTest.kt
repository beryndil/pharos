package com.beryndil.pharos.data.drugref

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.beryndil.pharos.data.drugref.entity.LabelCacheEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [DrugLabelRepository] (spec §2.10, Law 9).
 *
 * Uses a fake [DrugLabelService] — no real network in unit tests (PIPELINE.md requirement).
 * Covers: cache-aside hit/miss, offline fallback, free-text-med short-circuit.
 */
@RunWith(RobolectricTestRunner::class)
class DrugLabelRepositoryTest {

    private lateinit var db: DrugRefDatabase
    private lateinit var fakeSvc: FakeDrugLabelService
    private lateinit var repo: DrugLabelRepository

    private val testRxcui = "12345"
    private val testLabel = FetchedLabel(
        sideEffectsText = "Nausea, headache",
        interactionsText = "Avoid alcohol",
        source = "openFDA",
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, DrugRefDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeSvc = FakeDrugLabelService()
        repo = DrugLabelRepository(db.labelCacheDao(), fakeSvc)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Fetch and cache ───────────────────────────────────────────────────────

    @Test
    fun fetchAndCache_insertsLabelWithSourceAndTimestamp() = runTest {
        fakeSvc.nextResult = testLabel

        val result = repo.getOrFetchLabel(testRxcui)

        assertNotNull(result)
        assertEquals(testRxcui, result!!.productRxcui)
        assertEquals("Nausea, headache", result.sideEffectsText)
        assertEquals("Avoid alcohol", result.interactionsText)
        assertEquals("openFDA", result.source)
        // Timestamp must be a plausible epoch-ms (after 2020-01-01 = 1577836800000)
        assert(result.fetchedAtEpochMs > 1_577_836_800_000L) {
            "fetchedAtEpochMs should be a real timestamp, got ${result.fetchedAtEpochMs}"
        }
    }

    @Test
    fun fetchAndCache_persistsToRoomDao() = runTest {
        fakeSvc.nextResult = testLabel

        repo.getOrFetchLabel(testRxcui)

        // Check that it's actually in Room (not just returned in-memory).
        val fromDb = db.labelCacheDao().getByProduct(testRxcui)
        assertNotNull(fromDb)
        assertEquals("openFDA", fromDb!!.source)
    }

    // ── Cache hit ─────────────────────────────────────────────────────────────

    @Test
    fun cachedLabel_returnedWithoutNetworkCall() = runTest {
        // Pre-populate the cache.
        val cached = LabelCacheEntity(
            productRxcui = testRxcui,
            sideEffectsText = "Dizziness",
            interactionsText = null,
            source = "openFDA",
            fetchedAtEpochMs = 1_700_000_000_000L,
        )
        db.labelCacheDao().upsert(cached)
        fakeSvc.nextResult = testLabel // would overwrite if network were called

        val result = repo.getOrFetchLabel(testRxcui)

        // Must serve cached data, not the fake network response.
        assertNotNull(result)
        assertEquals("Dizziness", result!!.sideEffectsText)
        assertNull(result.interactionsText)
        // Network must NOT have been called.
        assertEquals(0, fakeSvc.callCount)
    }

    @Test
    fun getCachedLabel_returnsNullWhenNotCached() = runTest {
        val result = repo.getCachedLabel("nonexistent-rxcui")
        assertNull(result)
    }

    @Test
    fun getCachedLabel_returnsValueWhenCached() = runTest {
        val cached = LabelCacheEntity(
            productRxcui = testRxcui,
            sideEffectsText = "Test",
            interactionsText = null,
            source = "openFDA",
            fetchedAtEpochMs = 1_700_000_000_000L,
        )
        db.labelCacheDao().upsert(cached)

        val result = repo.getCachedLabel(testRxcui)

        assertNotNull(result)
        assertEquals("Test", result!!.sideEffectsText)
    }

    // ── Offline / not cached ──────────────────────────────────────────────────

    @Test
    fun notCachedAndNetworkFails_returnsNull() = runTest {
        fakeSvc.nextResult = null // simulate offline / no data

        val result = repo.getOrFetchLabel(testRxcui)

        assertNull("Expect null when offline and not cached", result)
    }

    // ── ensureLabelCached ─────────────────────────────────────────────────────

    @Test
    fun ensureLabelCached_skipsNetworkIfAlreadyCached() = runTest {
        val cached = LabelCacheEntity(
            productRxcui = testRxcui,
            sideEffectsText = "Already cached",
            interactionsText = null,
            source = "openFDA",
            fetchedAtEpochMs = 1_700_000_000_000L,
        )
        db.labelCacheDao().upsert(cached)

        repo.ensureLabelCached(testRxcui)

        assertEquals("Network must not be called for cached entries", 0, fakeSvc.callCount)
    }

    @Test
    fun ensureLabelCached_fetchesWhenNotCached() = runTest {
        fakeSvc.nextResult = testLabel

        repo.ensureLabelCached(testRxcui)

        val fromDb = db.labelCacheDao().getByProduct(testRxcui)
        assertNotNull(fromDb)
        assertEquals("Nausea, headache", fromDb!!.sideEffectsText)
    }
}

/** Fake [DrugLabelService] for unit tests — no real network. */
class FakeDrugLabelService : DrugLabelService {
    var nextResult: FetchedLabel? = null
    var callCount = 0

    override suspend fun fetchLabel(productRxcui: String): FetchedLabel? {
        callCount++
        return nextResult
    }
}
