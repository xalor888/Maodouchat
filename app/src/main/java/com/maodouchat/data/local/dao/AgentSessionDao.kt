package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.maodouchat.data.local.entity.AgentSessionEntity

@Dao
interface AgentSessionDao {
    @Query("SELECT * FROM agent_sessions WHERE ownerUserId = :ownerUserId ORDER BY sortOrder ASC")
    fun sessionsForOwnerBlocking(ownerUserId: String): List<AgentSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAllBlocking(entities: List<AgentSessionEntity>)

    @Query("DELETE FROM agent_sessions WHERE ownerUserId = :ownerUserId")
    fun deleteForUserBlocking(ownerUserId: String): Int

    @Transaction
    fun replaceAllBlocking(ownerUserId: String, entities: List<AgentSessionEntity>) {
        deleteForUserBlocking(ownerUserId)
        if (entities.isNotEmpty()) upsertAllBlocking(entities)
    }
}
