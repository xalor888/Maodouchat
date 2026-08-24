package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maodouchat.data.local.entity.AiTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiTaskDao {

    @Query(
        """
        SELECT * FROM ai_tasks
        WHERE chatId = :chatId
        ORDER BY isCompleted ASC,
            CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END ASC,
            dueAt ASC,
            createdAt DESC
        """
    )
    fun observeByChatId(chatId: String): Flow<List<AiTaskEntity>>

    @Query("SELECT * FROM ai_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getById(taskId: String): AiTaskEntity?

    @Query(
        """
        SELECT * FROM ai_tasks
        ORDER BY isCompleted ASC, updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun listRecent(limit: Int): List<AiTaskEntity>

    @Query(
        """
        SELECT * FROM ai_tasks
        WHERE isCompleted = 0
            AND dueAt IS NOT NULL
            AND remindedAt IS NULL
        ORDER BY dueAt ASC
        LIMIT 1000
        """
    )
    suspend fun getPendingReminders(): List<AiTaskEntity>

    @Query("SELECT id FROM ai_tasks WHERE chatId = :chatId")
    suspend fun getIdsByChatId(chatId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<AiTaskEntity>)

    @Query(
        """
        UPDATE ai_tasks
        SET isCompleted = :completed,
            completedAt = :completedAt,
            updatedAt = :updatedAt,
            remindedAt = CASE WHEN :completed THEN remindedAt ELSE NULL END
        WHERE id = :taskId
        """
    )
    suspend fun setCompleted(taskId: String, completed: Boolean, completedAt: Long?, updatedAt: Long)

    @Query("UPDATE ai_tasks SET remindedAt = :remindedAt WHERE id = :taskId AND isCompleted = 0")
    suspend fun markReminded(taskId: String, remindedAt: Long)

    @Query("DELETE FROM ai_tasks WHERE id = :taskId")
    suspend fun delete(taskId: String)

    /** 删除完成时间早于 :before 的已完成任务（完成状态保留期清理）。 */
    @Query("DELETE FROM ai_tasks WHERE isCompleted = 1 AND completedAt IS NOT NULL AND completedAt < :before")
    suspend fun deleteCompletedOlderThan(before: Long)

    @Query("DELETE FROM ai_tasks WHERE chatId = :chatId")
    suspend fun deleteByChatId(chatId: String)

    @Query("DELETE FROM ai_tasks WHERE chatId = :chatId AND isCompleted = 1")
    suspend fun deleteCompletedByChatId(chatId: String)

    @Query("DELETE FROM ai_tasks")
    suspend fun deleteAll()
}
