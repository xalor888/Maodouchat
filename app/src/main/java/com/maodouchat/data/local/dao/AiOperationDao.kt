package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.AiOperationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiOperationDao {
    @Query(
        """
        SELECT * FROM ai_operations
        WHERE ownerUserId = :ownerUserId
            AND chatId = :chatId
            AND state IN ('QUEUED', 'RUNNING', 'FAILED')
        ORDER BY
            CASE state
                WHEN 'RUNNING' THEN 0
                WHEN 'QUEUED' THEN 1
                ELSE 2
            END,
            createdAt ASC,
            updatedAt DESC
        """
    )
    fun observeActionable(ownerUserId: String, chatId: String): Flow<List<AiOperationEntity>>

    @Query("SELECT * FROM ai_operations WHERE id = :operationId LIMIT 1")
    suspend fun get(operationId: String): AiOperationEntity?

    @Query(
        """
        SELECT * FROM ai_operations
        WHERE ownerUserId = :ownerUserId
            AND chatId = :chatId
            AND state = 'RUNNING'
        LIMIT 1
        """
    )
    suspend fun getRunning(ownerUserId: String, chatId: String): AiOperationEntity?

    @Query(
        """
        SELECT * FROM ai_operations
        WHERE ownerUserId = :ownerUserId
            AND chatId = :chatId
            AND state = 'QUEUED'
        ORDER BY createdAt ASC
        LIMIT 1
        """
    )
    suspend fun getNextQueued(ownerUserId: String, chatId: String): AiOperationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(operation: AiOperationEntity)

    @Query(
        """
        UPDATE ai_operations
        SET state = 'QUEUED',
            lastErrorCode = NULL,
            updatedAt = :updatedAt
        WHERE id = :operationId
            AND state IN ('FAILED', 'QUEUED')
        """
    )
    suspend fun markQueued(operationId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE ai_operations
        SET state = 'RUNNING',
            attempts = attempts + 1,
            lastErrorCode = NULL,
            updatedAt = :updatedAt
        WHERE id = :operationId
            AND state IN ('QUEUED', 'FAILED')
        """
    )
    suspend fun markRunning(operationId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE ai_operations
        SET state = 'SUCCEEDED', lastErrorCode = NULL, updatedAt = :updatedAt
        WHERE id = :operationId AND state = 'RUNNING'
        """
    )
    suspend fun markSucceeded(operationId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE ai_operations
        SET state = 'FAILED', lastErrorCode = :errorCode, updatedAt = :updatedAt
        WHERE id = :operationId AND state IN ('QUEUED', 'RUNNING', 'FAILED')
        """
    )
    suspend fun markFailed(operationId: String, errorCode: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE ai_operations
        SET state = 'CANCELLED', lastErrorCode = NULL, updatedAt = :updatedAt
        WHERE id = :operationId AND state IN ('QUEUED', 'RUNNING', 'FAILED')
        """
    )
    suspend fun markCancelled(operationId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE ai_operations
        SET state = 'FAILED', lastErrorCode = 'INTERRUPTED', updatedAt = :updatedAt
        WHERE ownerUserId = :ownerUserId AND chatId = :chatId AND state IN ('QUEUED', 'RUNNING')
        """
    )
    suspend fun recoverInterrupted(ownerUserId: String, chatId: String, updatedAt: Long): Int

    @Query("DELETE FROM ai_operations WHERE id = :operationId")
    suspend fun delete(operationId: String)

    @Query("DELETE FROM ai_operations WHERE chatId = :chatId")
    suspend fun deleteByChatId(chatId: String)

    @Query("DELETE FROM ai_operations")
    suspend fun deleteAll()

    @Query(
        """
        DELETE FROM ai_operations
        WHERE state IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND updatedAt < :before
        """
    )
    suspend fun pruneTerminal(before: Long): Int
}
