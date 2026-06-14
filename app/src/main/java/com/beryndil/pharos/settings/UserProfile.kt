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
    val insuranceProvider: String? = null,
    val insuranceMemberId: String? = null,
    val emergencyContactName: String? = null,
    val emergencyContactPhone: String? = null,
) {
    fun isEmpty(): Boolean =
        name.isNullOrBlank() && dateOfBirth.isNullOrBlank() && phone.isNullOrBlank() &&
            address.isNullOrBlank() && allergies.isNullOrBlank() &&
            insuranceProvider.isNullOrBlank() && insuranceMemberId.isNullOrBlank() &&
            emergencyContactName.isNullOrBlank() && emergencyContactPhone.isNullOrBlank()
}

class UserProfileRepository(private val settingDao: SettingDao) {

    suspend fun getProfile(): UserProfile = UserProfile(
        name = settingDao.get(KEY_NAME)?.value,
        dateOfBirth = settingDao.get(KEY_DOB)?.value,
        phone = settingDao.get(KEY_PHONE)?.value,
        address = settingDao.get(KEY_ADDRESS)?.value,
        allergies = settingDao.get(KEY_ALLERGIES)?.value,
        insuranceProvider = settingDao.get(KEY_INSURANCE_PROVIDER)?.value,
        insuranceMemberId = settingDao.get(KEY_INSURANCE_MEMBER_ID)?.value,
        emergencyContactName = settingDao.get(KEY_EMERGENCY_CONTACT_NAME)?.value,
        emergencyContactPhone = settingDao.get(KEY_EMERGENCY_CONTACT_PHONE)?.value,
    )

    suspend fun saveProfile(profile: UserProfile, nowMs: Long = System.currentTimeMillis()) {
        upsertOrDelete(KEY_NAME, profile.name, nowMs)
        upsertOrDelete(KEY_DOB, profile.dateOfBirth, nowMs)
        upsertOrDelete(KEY_PHONE, profile.phone, nowMs)
        upsertOrDelete(KEY_ADDRESS, profile.address, nowMs)
        upsertOrDelete(KEY_ALLERGIES, profile.allergies, nowMs)
        upsertOrDelete(KEY_INSURANCE_PROVIDER, profile.insuranceProvider, nowMs)
        upsertOrDelete(KEY_INSURANCE_MEMBER_ID, profile.insuranceMemberId, nowMs)
        upsertOrDelete(KEY_EMERGENCY_CONTACT_NAME, profile.emergencyContactName, nowMs)
        upsertOrDelete(KEY_EMERGENCY_CONTACT_PHONE, profile.emergencyContactPhone, nowMs)
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
        const val KEY_INSURANCE_PROVIDER = "profile.insurance_provider"
        const val KEY_INSURANCE_MEMBER_ID = "profile.insurance_member_id"
        const val KEY_EMERGENCY_CONTACT_NAME = "profile.emergency_contact_name"
        const val KEY_EMERGENCY_CONTACT_PHONE = "profile.emergency_contact_phone"
    }
}
