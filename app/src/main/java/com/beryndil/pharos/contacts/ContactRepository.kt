package com.beryndil.pharos.contacts

import com.beryndil.pharos.data.regimen.dao.PharmacyDao
import com.beryndil.pharos.data.regimen.dao.PrescriberDao
import com.beryndil.pharos.data.regimen.entity.PharmacyEntity
import com.beryndil.pharos.data.regimen.entity.PrescriberEntity
import kotlinx.coroutines.flow.Flow
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
) {

    // ── Observe ───────────────────────────────────────────────────────────

    fun observePrescribers(): Flow<List<PrescriberEntity>> = prescriberDao.observeAll()

    fun observePharmacies(): Flow<List<PharmacyEntity>> = pharmacyDao.observeAll()

    // ── Remember (called from Add/Edit medication save path) ──────────────

    /**
     * Ensures a prescriber with this [name] exists in the store. If an entry already exists
     * (case-insensitive), updates its phone if [phone] is non-null and non-blank. If no entry
     * exists, inserts a new one. No-ops on blank name.
     */
    suspend fun rememberPrescriber(name: String, phone: String?) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val existing = prescriberDao.getByName(trimmed)
        if (existing == null) {
            prescriberDao.upsert(
                PrescriberEntity(
                    id = UUID.randomUUID().toString(),
                    name = trimmed,
                    phone = phone?.trim()?.ifEmpty { null },
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
        } else if (phone != null && phone.trim().isNotEmpty() && existing.phone != phone.trim()) {
            prescriberDao.update(existing.copy(phone = phone.trim()))
        }
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
        if (existing == null) {
            pharmacyDao.upsert(
                PharmacyEntity(
                    id = UUID.randomUUID().toString(),
                    name = trimmed,
                    phone = phone?.trim()?.ifEmpty { null },
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
        } else if (phone != null && phone.trim().isNotEmpty() && existing.phone != phone.trim()) {
            pharmacyDao.update(existing.copy(phone = phone.trim()))
        }
    }

    // ── Manage screen CRUD ────────────────────────────────────────────────

    suspend fun updatePrescriber(prescriber: PrescriberEntity) = prescriberDao.update(prescriber)

    suspend fun updatePharmacy(pharmacy: PharmacyEntity) = pharmacyDao.update(pharmacy)

    suspend fun deletePrescriber(id: String) = prescriberDao.deleteById(id)

    suspend fun deletePharmacy(id: String) = pharmacyDao.deleteById(id)
}
