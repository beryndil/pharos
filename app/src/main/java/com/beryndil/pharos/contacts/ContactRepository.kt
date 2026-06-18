package com.beryndil.pharos.contacts

import com.beryndil.pharos.data.regimen.dao.PharmacyDao
import com.beryndil.pharos.data.regimen.dao.PrescriberDao
import com.beryndil.pharos.data.regimen.dao.SettingDao
import com.beryndil.pharos.data.regimen.entity.PharmacyEntity
import com.beryndil.pharos.data.regimen.entity.PrescriberEntity
import com.beryndil.pharos.data.regimen.entity.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Manages saved prescriber and pharmacy contacts (spec V1.3-F1).
 *
 * All data is local (Law 4 — no off-device transmission). The repository is the single
 * write path for both auto-remember (from Add/Edit medication save) and user-driven edits
 * from the Saved Contacts manage screen.
 *
 * Deleting a saved contact does NOT alter medications that already reference it — medications
 * retain their stored name/phone strings (association is by value, not by FK).
 */
class ContactRepository(
    private val prescriberDao: PrescriberDao,
    private val pharmacyDao: PharmacyDao,
    private val settingDao: SettingDao,
) {

    // ── Defaults ──────────────────────────────────────────────────────────

    fun observeDefaultPrescriberId(): Flow<String?> =
        settingDao.observeByKey(KEY_DEFAULT_PRESCRIBER).map { it?.value }

    fun observeDefaultPharmacyId(): Flow<String?> =
        settingDao.observeByKey(KEY_DEFAULT_PHARMACY).map { it?.value }

    suspend fun getDefaultPrescriber(): PrescriberEntity? {
        val id = settingDao.get(KEY_DEFAULT_PRESCRIBER)?.value ?: return null
        return prescriberDao.getById(id)
    }

    suspend fun getDefaultPharmacy(): PharmacyEntity? {
        val id = settingDao.get(KEY_DEFAULT_PHARMACY)?.value ?: return null
        return pharmacyDao.getById(id)
    }

    suspend fun setDefaultPrescriberId(id: String?) {
        val nowMs = System.currentTimeMillis()
        if (id == null) settingDao.deleteByKey(KEY_DEFAULT_PRESCRIBER)
        else settingDao.upsert(SettingEntity(KEY_DEFAULT_PRESCRIBER, id, nowMs))
    }

    suspend fun setDefaultPharmacyId(id: String?) {
        val nowMs = System.currentTimeMillis()
        if (id == null) settingDao.deleteByKey(KEY_DEFAULT_PHARMACY)
        else settingDao.upsert(SettingEntity(KEY_DEFAULT_PHARMACY, id, nowMs))
    }

    // ── Observe ───────────────────────────────────────────────────────────

    fun observePrescribers(): Flow<List<PrescriberEntity>> = prescriberDao.observeAll()

    fun observePharmacies(): Flow<List<PharmacyEntity>> = pharmacyDao.observeAll()

    // ── Remember (called from Add/Edit medication save path) ──────────────

    /**
     * Ensures a prescriber with this [name] exists in the store. If an entry already exists
     * (case-insensitive), updates its phone if [phone] is non-null and non-blank. If no entry
     * exists, inserts a new one. No-ops on blank name.
     */
    suspend fun rememberPrescriber(name: String, phone: String?, practice: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val existing = prescriberDao.getByName(trimmed)
        val contactId: String
        if (existing == null) {
            val newId = UUID.randomUUID().toString()
            prescriberDao.upsert(
                PrescriberEntity(
                    id = newId,
                    name = trimmed,
                    phone = phone?.trim()?.ifEmpty { null },
                    practice = practice?.trim()?.ifEmpty { null },
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
            contactId = newId
        } else {
            val newPhone = if (phone != null && phone.trim().isNotEmpty()) phone.trim() else existing.phone
            val newPractice = if (!practice.isNullOrBlank()) practice.trim() else existing.practice
            if (newPhone != existing.phone || newPractice != existing.practice) {
                prescriberDao.update(existing.copy(phone = newPhone, practice = newPractice))
            }
            contactId = existing.id
        }
        if (settingDao.get(KEY_DEFAULT_PRESCRIBER) == null) setDefaultPrescriberId(contactId)
    }

    /**
     * Ensures a pharmacy with this [name] exists in the store. If an entry already exists
     * (case-insensitive), updates its phone if [phone] is non-null and non-blank. If no entry
     * exists, inserts a new one. No-ops on blank name.
     */
    suspend fun rememberPharmacy(name: String, phone: String?) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val existing = pharmacyDao.getByName(trimmed)
        val contactId: String
        if (existing == null) {
            val newId = UUID.randomUUID().toString()
            pharmacyDao.upsert(
                PharmacyEntity(
                    id = newId,
                    name = trimmed,
                    phone = phone?.trim()?.ifEmpty { null },
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
            contactId = newId
        } else {
            if (phone != null && phone.trim().isNotEmpty() && existing.phone != phone.trim()) {
                pharmacyDao.update(existing.copy(phone = phone.trim()))
            }
            contactId = existing.id
        }
        if (settingDao.get(KEY_DEFAULT_PHARMACY) == null) setDefaultPharmacyId(contactId)
    }

    // ── Manage screen CRUD ────────────────────────────────────────────────

    suspend fun updatePrescriber(prescriber: PrescriberEntity) = prescriberDao.update(prescriber)

    suspend fun updatePharmacy(pharmacy: PharmacyEntity) = pharmacyDao.update(pharmacy)

    suspend fun deletePrescriber(id: String) = prescriberDao.deleteById(id)

    suspend fun deletePharmacy(id: String) = pharmacyDao.deleteById(id)

    companion object {
        const val KEY_DEFAULT_PRESCRIBER = "contacts.default_prescriber_id"
        const val KEY_DEFAULT_PHARMACY = "contacts.default_pharmacy_id"
    }
}
