package com.maodouchat.data.repository

import com.maodouchat.data.local.dao.AiSummaryCacheDao
import com.maodouchat.data.local.entity.AiSummaryCacheEntity

class AiSummaryRepository(private val dao: AiSummaryCacheDao) {

    suspend fun getSummary(cacheKey: String): AiSummaryCacheEntity? =
        dao.get(cacheKey)

    suspend fun getSummariesForChat(chatId: String, limit: Int = 30): List<AiSummaryCacheEntity> =
        dao.getByChatId(chatId, limit.coerceIn(1, 80))

    suspend fun deleteByChatId(chatId: String) = dao.deleteByChatId(chatId)

    /** 删除创建时间早于 :before 的总结缓存（保留期清理）。 */
    suspend fun pruneOlderThan(before: Long) = dao.deleteOlderThan(before)

    suspend fun saveSummary(
        cacheKey: String,
        chatId: String,
        startMessageId: String,
        endMessageId: String,
        messageCount: Int,
        summary: String,
        createdAt: Long = System.currentTimeMillis()
    ): AiSummaryCacheEntity {
        val entity = AiSummaryCacheEntity(
            cacheKey = cacheKey,
            chatId = chatId,
            startMessageId = startMessageId,
            endMessageId = endMessageId,
            messageCount = messageCount,
            summary = summary.take(3_000),
            createdAt = createdAt
        )
        dao.upsert(entity)
        return entity
    }
}
