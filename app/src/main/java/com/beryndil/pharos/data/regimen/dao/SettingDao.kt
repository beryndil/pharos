package com.beryndil.pharos.data.regimen.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.regimen.entity.SettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingDao {

    /** REPLACE semantics: a setting represents current state, not a history. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: SettingEntity)

    @Query("SELECT * FROM settings WHERE key = :key")
    suspend fun get(key: String): SettingEntity?

    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>
}
