package com.beryndil.pharos.data.regimen.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A remembered pharmacy contact (spec V1.3-F1).
 *
 * Stored locally in the regimen DB (Law 4 — no off-device transmission). Entries are created
 * automatically when a medication is saved with a new pharmacy name, and can be edited or
 * deleted by the user from the Saved Contacts screen. Deleting an entry does NOT alter
 * medications that already reference it — those rows retain their stored strings.
 */
@Entity(tableName = "pharmacies")
data class PharmacyEntity(
    /** UUID assigned at creation. Never reused. */
    @PrimaryKey val id: String,

    /** Display name as typed by the user. */
    val name: String,

    /** Optional phone number string. */
    val phone: String?,

    val createdAtEpochMs: Long,
)
