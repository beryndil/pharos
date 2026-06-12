package com.beryndil.pharos.data.regimen.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A key-value settings store for app configuration persisted in the regimen database.
 *
 * REPLACE-on-conflict is the correct semantic for settings — a setting is a current value,
 * not a history. All historical data lives in [DoseInstanceEntity] (append-only); settings
 * are not historical records and may be overwritten.
 */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAtEpochMs: Long,
)
