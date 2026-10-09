package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.repository.AiProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * B4 · 智能归档建议（纯本地 SQLCipher，无服务端调用）。
 *
 * 建议结果持久化到独立 SQLCipher 库（AiProfileRepository），UI 可在会话列表顶部
 * 「智能归档建议」卡片直接展示；采纳后走现有 chatDao.setArchived 流程。
 *
 * 实现已按簇拆到 AiArchiveScoring（打分）与 AiArchiveReason（原因文案），
 * 这里只保留门禁、公开类型与统一入口。
 */
object AiArchiveSuggestion {

    fun isAllowed(context: Context): Boolean = true

    data class Suggestion(
        val chatId: String,
        val score: Int,
        val reason: String
    )

    /** 重新计算全部建议并覆盖落库；返回按分数降序的建议列表。 */
    suspend fun refresh(context: Context, database: AppDatabase): List<Suggestion> {
        val suggestions = withContext(Dispatchers.IO) {
            AiArchiveScoring.compute(context, database)
        }
        val repository = AiProfileRepository.getInstance(context)
        withContext(Dispatchers.IO) {
            repository.clearArchiveSuggestions()
            suggestions.forEach { repository.saveArchiveSuggestion(it.chatId, it.score, it.reason) }
        }
        return suggestions
    }

    /** 读取上次计算并落库的建议（仅本地）。 */
    suspend fun cached(context: Context): List<Suggestion> =
        withContext(Dispatchers.IO) {
            AiProfileRepository.getInstance(context).listArchiveSuggestions().map { row ->
                Suggestion(row.chatId, row.score, row.reason)
            }
        }
}
