package com.beryndil.pharos.settings

import com.beryndil.pharos.data.regimen.dao.SettingDao
import com.beryndil.pharos.data.regimen.entity.SettingEntity

/** User's personal profile shown at the top of PDF medication exports. All fields are optional. */
data class UserProfile(
    val name: String? = null,
    val dateOfBirth: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val allergies: String? = null,
) {
    fun isEmpty(): Boolean =
        name.isNullOrBlank() && dateOfBirth.isNullOrBlank() && phone.isNullOrBlank() &&
            address.isNullOrBlank() && allergies.isNullOrBlank()
}

class UserProfileRepository(private val settingDao: SettingDao) {

    suspend fun getProfile(): UserProfile = UserProfile(
        name = settingDao.get(KEY_NAME)?.value,
        dateOfBirth = settingDao.get(KEY_DOB)?.value,
        phone = settingDao.get(KEY_PHONE)?.value,
        address = settingDao.get(KEY_ADDRESS)?.value,
        allergies = settingDao.get(KEY_ALLERGIES)?.value,
    )

    suspend fun saveProfile(profile: UserProfile, nowMs: Long = System.currentTimeMillis()) {
        upsertOrDelete(KEY_NAME, profile.name, nowMs)
        upsertOrDelete(KEY_DOB, profile.dateOfBirth, nowMs)
        upsertOrDelete(KEY_PHONE, profile.phone, nowMs)
        upsertOrDelete(KEY_ADDRESS, profile.address, nowMs)
        upsertOrDelete(KEY_ALLERGIES, profile.allergies, nowMs)
    }

    private suspend fun upsertOrDelete(key: String, value: String?, nowMs: Long) {
        if (value.isNullOrBlank()) {
            settingDao.deleteByKey(key)
        } else {
            settingDao.upsert(SettingEntity(key = key, value = value.trim(), updatedAtEpochMs = nowMs))
        }
    }

    companion object {
        const val KEY_NAME = "profile.name"
        const val KEY_DOB = "profile.dob"
        const val KEY_PHONE = "profile.phone"
        const val KEY_ADDRESS = "profile.address"
        const val KEY_ALLERGIES = "profile.allergies"
    }
}
