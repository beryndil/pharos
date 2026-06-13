package com.beryndil.pharos.onboarding

import com.beryndil.pharos.data.regimen.dao.SettingDao
import com.beryndil.pharos.data.regimen.entity.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OnboardingRepository] against a real instance backed by a fake [SettingDao].
 *
 * Tests the real class (not [FakeOnboardingRepository]) to verify that [markComplete] writes
 * through the DAO and [isComplete] reads back correctly. Pure-JVM; no Robolectric needed.
 */
class OnboardingRepositoryTest {

    private fun makeRepo(): Pair<OnboardingRepository, InMemorySettingDao> {
        val dao = InMemorySettingDao()
        return OnboardingRepository(dao) to dao
    }

    @Test
    fun isComplete_returnsFalse_whenFlagAbsent() = runTest {
        val (repo, _) = makeRepo()
        assertFalse(repo.isComplete())
    }

    @Test
    fun markComplete_persistsFlag_isCompleteReturnsTrue() = runTest {
        val (repo, _) = makeRepo()
        assertFalse(repo.isComplete())
        repo.markComplete()
        assertTrue(repo.isComplete())
    }

    @Test
    fun markComplete_isIdempotent_doesNotThrow() = runTest {
        val (repo, _) = makeRepo()
        repo.markComplete()
        repo.markComplete()  // second call must not throw or reset the flag
        assertTrue(repo.isComplete())
    }

    @Test
    fun isComplete_readsThroughDao_reflectsExternalWrite() = runTest {
        val (repo, dao) = makeRepo()
        // Simulate a flag written by another code path (e.g., migration or future feature).
        dao.upsert(
            SettingEntity(
                key = OnboardingRepository.KEY_ONBOARDING_COMPLETE,
                value = "true",
                updatedAtEpochMs = 0L,
            ),
        )
        assertTrue(repo.isComplete())
    }
}

// ── Fake DAO ──────────────────────────────────────────────────────────────────────────────────

/**
 * In-memory [SettingDao] implementation for unit tests.
 * Shared with [OnboardingViewModelTest]'s inline fake but kept independent to avoid
 * coupling test classes.
 */
private class InMemorySettingDao : SettingDao {

    private val store = mutableMapOf<String, SettingEntity>()

    override suspend fun upsert(setting: SettingEntity) {
        store[setting.key] = setting
    }

    override suspend fun get(key: String): SettingEntity? = store[key]

    override fun observeAll(): Flow<List<SettingEntity>> =
        MutableStateFlow(store.values.toList())

    override suspend fun getAll(): List<SettingEntity> = store.values.toList()

    override fun observeByKey(key: String): Flow<SettingEntity?> =
        MutableStateFlow(store[key])
}
