package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.maodouchat.data.local.entity.AiSummaryCacheEntity

@Dao
interface AiSummaryCacheDao {

    @Query("SELECT * FROM ai_summary_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun get(cacheKey: String): AiSummaryCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: AiSummaryCacheEntity)

    @Transaction
    suspend fun upsertIfNewerWhileCurrent(
        summary: AiSummaryCacheEntity,
        isSessionCurrent: () -> Boolean
    ): Int {
        if (!isSessionCurrent()) return IMPORT_SKIPPED_SESSION_CHANGED
        val existing = get(summary.cacheKey)
        if (!isSessionCurrent()) return IMPORT_SKIPPED_SESSION_CHANGED
        if (existing != null && existing.createdAt > summary.createdAt) {
            return if (isSessionCurrent()) IMPORT_HANDLED else IMPORT_SKIPPED_SESSION_CHANGED
        }
        // Final session check before writing: once upsert executes, the data is committed
        // and must be ACKed to avoid redundant re-pull.
        if (!isSessionCurrent()) return IMPORT_SKIPPED_SESSION_CHANGED
        upsert(summary)
        // Write已落盘，必须返回 UPDATED 让调用方 ACK，否则服务端会无限重推。
        return IMPORT_UPDATED
    }

    @Query("SELECT * FROM ai_summary_cache WHERE chatId = :chatId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByChatId(chatId: String, limit: Int): List<AiSummaryCacheEntity>

    @Query("DELETE FROM ai_summary_cache WHERE chatId = :chatId")
    suspend fun deleteByChatId(chatId: String)

    /** 删除创建时间早于 :before 的总结缓存（按时间保留期清理）。 */
    @Query("DELETE FROM ai_summary_cache WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM ai_summary_cache")
    suspend fun deleteAll()

    companion object {
        const val IMPORT_SKIPPED_SESSION_CHANGED = 0
        const val IMPORT_HANDLED = 1
        const val IMPORT_UPDATED = 2
    }
}
