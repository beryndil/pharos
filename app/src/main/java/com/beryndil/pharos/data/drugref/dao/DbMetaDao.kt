package com.beryndil.pharos.data.drugref.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.drugref.entity.DbMetaEntity

@Dao
interface DbMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DbMetaEntity>)

    /** Returns the value for [key], or null if the key is absent. */
    @Query("SELECT * FROM db_meta WHERE key = :key")
    suspend fun get(key: String): DbMetaEntity?

    /** Returns all provenance records — used for the drug-reference UI (Law 9). */
    @Query("SELECT * FROM db_meta")
    suspend fun all(): List<DbMetaEntity>
}
