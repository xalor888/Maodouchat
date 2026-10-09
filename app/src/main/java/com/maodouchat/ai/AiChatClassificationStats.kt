package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.repository.AiProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 会话级分类统计簇：重算分类统计并落库 / 读取缓存（纯本地 SQLCipher）。 */
object AiChatClassificationStats {

    /** 重算某会话分类统计并落库；返回类别统计（按计数降序）。 */
    suspend fun classifyChat(context: Context, database: AppDatabase, chatId: String): List<AiProfileRepository.CategoryCount> {
        val tallies = withContext(Dispatchers.IO) {
            // 8.48 修复：按会话查询——此前「全库最新 2000 条后 filter chatId」在活跃大库下
            // 目标会话不在最新窗口时统计为空/失真
            val messages = database.messageDao()
                .getSearchableMessagesForChat(chatId, limit = CLASSIFY_SCAN_MESSAGES)
                .map { it.toDomain() }
                .takeLast(CLASSIFY_SAMPLE_LIMIT)
            val counts = HashMap<AiMessageClassifier.Category, Int>()
            // 9.231：置信度按类别均值——此前把全会话平均置信度复制给每个类别，
            // 低命中类别也显示高置信度，UI 排序/展示失真
            val confSums = HashMap<AiMessageClassifier.Category, Double>()
            for (message in messages) {
                val result = AiClassifyLexicon.classifyText(message.parsedContent())
                counts[result.category] = (counts[result.category] ?: 0) + 1
                confSums[result.category] = (confSums[result.category] ?: 0.0) + result.confidence
            }
            counts.map { (category, count) ->
                AiProfileRepository.CategoryCount(
                    category = category.wire,
                    count = count,
                    confidence = (confSums[category] ?: 0.0) / count
                )
            }.sortedByDescending { it.count }
        }
        withContext(Dispatchers.IO) {
            AiProfileRepository.getInstance(context).saveChatClasses(chatId, tallies)
        }
        return tallies
    }

    /** 读取上次分类统计（仅本地）。 */
    suspend fun cached(context: Context, chatId: String): List<AiProfileRepository.CategoryCount> =
        withContext(Dispatchers.IO) {
            AiProfileRepository.getInstance(context).getChatClasses(chatId)
        }

    private const val CLASSIFY_SCAN_MESSAGES = 2_000
    private const val CLASSIFY_SAMPLE_LIMIT = 200
}
