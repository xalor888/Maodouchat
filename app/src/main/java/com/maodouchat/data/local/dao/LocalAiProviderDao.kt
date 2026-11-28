package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.maodouchat.data.local.entity.LocalAiProviderEntity

@Dao
interface LocalAiProviderDao {
    @Query("SELECT * FROM local_ai_providers WHERE ownerUserId = :ownerUserId ORDER BY sortOrder ASC")
    fun providersForOwnerBlocking(ownerUserId: String): List<LocalAiProviderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAllBlocking(entities: List<LocalAiProviderEntity>)

    @Query("DELETE FROM local_ai_providers WHERE ownerUserId = :ownerUserId")
    fun deleteForUserBlocking(ownerUserId: String): Int

    @Transaction
    fun replaceAllBlocking(ownerUserId: String, entities: List<LocalAiProviderEntity>) {
        deleteForUserBlocking(ownerUserId)
        if (entities.isNotEmpty()) upsertAllBlocking(entities)
    }
}
