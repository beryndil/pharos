package com.beryndil.pharos.onboarding

import com.beryndil.pharos.data.regimen.dao.SettingDao
import com.beryndil.pharos.data.regimen.entity.SettingEntity

/**
 * Persists onboarding completion state in the regimen DB settings table.
 *
 * Onboarding runs once on first launch. The completion flag is a simple key-value setting
 * (same pattern as reliability.* keys from Slice 4). [isComplete] is a suspend query so it
 * never blocks the main thread; [markComplete] is called once when the user exits onboarding.
 */
open class OnboardingRepository(private val settingDao: SettingDao) {

    /** Returns true if the user has already completed onboarding on this device. */
    open suspend fun isComplete(): Boolean =
        settingDao.get(KEY_ONBOARDING_COMPLETE)?.value == VALUE_TRUE

    /**
     * Marks onboarding as complete. Idempotent: calling it again has no effect on the user
     * experience, though the timestamp updates (last-write-wins, per settings semantics).
     */
    open suspend fun markComplete(nowMs: Long = System.currentTimeMillis()) {
        settingDao.upsert(
            SettingEntity(
                key = KEY_ONBOARDING_COMPLETE,
                value = VALUE_TRUE,
                updatedAtEpochMs = nowMs,
            ),
        )
    }

    companion object {
        const val KEY_ONBOARDING_COMPLETE = "onboarding.completed"
        private const val VALUE_TRUE = "true"
    }
}
