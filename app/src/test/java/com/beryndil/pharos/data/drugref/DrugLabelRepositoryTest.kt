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
 * Covers: cache-aside hit/miss, offline fallback, invalidate-and-refetch.
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
        warningsText = null,
        precautionsText = null,
        contraindicationsText = null,
        boxedWarningText = null,
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
        assert(result.fetchedAtEpochMs > 1_577_836_800_000L) {
            "fetchedAtEpochMs should be a real timestamp, got ${result.fetchedAtEpochMs}"
        }
    }

    @Test
    fun fetchAndCache_persistsToRoomDao() = runTest {
        fakeSvc.nextResult = testLabel

        repo.getOrFetchLabel(testRxcui)

        val fromDb = db.labelCacheDao().getByProduct(testRxcui)
        assertNotNull(fromDb)
        assertEquals("openFDA", fromDb!!.source)
    }

    // ── Cache hit ─────────────────────────────────────────────────────────────

    @Test
    fun cachedLabel_returnedWithoutNetworkCall() = runTest {
        val cached = LabelCacheEntity(
            productRxcui = testRxcui,
            sideEffectsText = "Dizziness",
            interactionsText = null,
            warningsText = null,
            precautionsText = null,
            contraindicationsText = null,
            boxedWarningText = null,
            source = "openFDA",
            fetchedAtEpochMs = 1_700_000_000_000L,
        )
        db.labelCacheDao().upsert(cached)
        fakeSvc.nextResult = testLabel

        val result = repo.getOrFetchLabel(testRxcui)

        assertNotNull(result)
        assertEquals("Dizziness", result!!.sideEffectsText)
        assertNull(result.interactionsText)
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
            warningsText = null,
            precautionsText = null,
            contraindicationsText = null,
            boxedWarningText = null,
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
        fakeSvc.nextResult = null

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
            warningsText = null,
            precautionsText = null,
            contraindicationsText = null,
            boxedWarningText = null,
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

    // ── invalidateAndRefetch ──────────────────────────────────────────────────

    @Test
    fun invalidateAndRefetch_clearsOldCacheAndFetchesNew() = runTest {
        val oldCached = LabelCacheEntity(
            productRxcui = testRxcui,
            sideEffectsText = "Old data",
            interactionsText = null,
            warningsText = null,
            precautionsText = null,
            contraindicationsText = null,
            boxedWarningText = null,
            source = "openFDA",
            fetchedAtEpochMs = 1_700_000_000_000L,
        )
        db.labelCacheDao().upsert(oldCached)
        fakeSvc.nextResult = testLabel

        val result = repo.invalidateAndRefetch(testRxcui)

        assertNotNull(result)
        assertEquals("Nausea, headache", result!!.sideEffectsText)
        assertEquals(1, fakeSvc.callCount)
    }
}

/** Fake [DrugLabelService] for unit tests — no real network. */
class FakeDrugLabelService : DrugLabelService {
    var nextResult: FetchedLabel? = null
    var callCount = 0

    override suspend fun fetchLabel(productRxcui: String, medicationName: String?): FetchedLabel? {
        callCount++
        return nextResult
    }
}
