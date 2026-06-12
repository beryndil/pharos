package com.beryndil.pharos.data.drugref.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.beryndil.pharos.data.drugref.entity.LabelCacheEntity

@Dao
interface LabelCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(label: LabelCacheEntity)

    @Query("SELECT * FROM label_cache WHERE productRxcui = :productRxcui")
    suspend fun getByProduct(productRxcui: String): LabelCacheEntity?
}
