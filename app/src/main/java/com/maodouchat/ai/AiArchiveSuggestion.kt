package com.maodouchat.ai

import android.content.Context
import com.maodouchat.R
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.repository.AiProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * B4 · 智能归档建议（纯本地 SQLCipher，无服务端调用）。
 *
 * 对每个会话基于本地消息统计打分（分数越高越建议归档）：
 * - 静置时长：最后活跃距今越久分越高；
 * - 消息密度：近 30 天消息越少分越高；
 * - 关键词权重：命中「已结束/已完成/通知类/活动/优惠」等收尾信号加分；
 * - 明确排除：未读消息、置顶、近期活跃、群公告类活跃会话。
 *
 * 建议结果持久化到独立 SQLCipher 库（AiProfileRepository），UI 可在会话列表顶部
 * 「智能归档建议」卡片直接展示；采纳后走现有 chatDao.setArchived 流程。
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
            compute(context, database)
        }
        val repository = AiProfileRepository(context)
        withContext(Dispatchers.IO) {
            repository.clearArchiveSuggestions()
            suggestions.forEach { repository.saveArchiveSuggestion(it.chatId, it.score, it.reason) }
        }
        return suggestions
    }

    /** 读取上次计算并落库的建议（仅本地）。 */
    suspend fun cached(context: Context): List<Suggestion> =
        withContext(Dispatchers.IO) {
            AiProfileRepository(context).listArchiveSuggestions().map { row ->
                Suggestion(row.chatId, row.score, row.reason)
            }
        }

    private suspend fun compute(context: Context, database: AppDatabase): List<Suggestion> {
        val now = System.currentTimeMillis()
        val chats = database.chatDao().getAllChats().firstOrNull().orEmpty()
        val chatById = chats.associateBy { it.id }

        val results = mutableListOf<Suggestion>()
        // 8.48 修复：逐会话查询（按 chatId 索引）——此前「全库最新 4000 条后按 chatId 过滤」
        // 在活跃大库下，静置旧会话的历史消息被新会话挤掉窗口，归档建议漏判/失真
        for ((chatId, chat) in chatById) {
            val chatMessages = database.messageDao()
                .getSearchableMessagesForChat(chatId, limit = 4_000)
                .map { it.toDomain() }
            if (chatMessages.isEmpty()) continue
            // 明确不归档：未读、置顶、归档中、最近 7 天活跃。
            if (chat.archived || chat.pinnedAt > 0L || chat.markedUnread || chat.unreadCount > 0) continue
            val lastActive = chatMessages.maxOfOrNull { it.timestamp } ?: chat.lastMessageTime
            if (now - lastActive < MIN_ACTIVE_SILENCE_MS) continue

            var score = 0
            val recent30 = chatMessages.count { now - it.timestamp <= 30L * DAY_MS }
            val idleDays = ((now - lastActive).coerceAtLeast(0L)) / DAY_MS
            score += ((idleDays / 7).coerceAtMost(6) * 2).toInt()          // 静置每 7 天 +2
            score += (30 - recent30.coerceAtMost(30)) / 2          // 近 30 天不活跃 +分

            var closingHints = 0
            var totalHints = 0
            chatMessages.takeLast(CLOSING_SCAN_MESSAGES).forEach { message ->
                val text = message.parsedContent()
                totalHints++
                if (CLOSING_HINTS.any { text.contains(it) }) closingHints++
            }
            if (totalHints > 0) {
                val ratio = closingHints.toDouble() / totalHints
                if (ratio >= 0.4) score += 3
            }
            if (score <= MIN_ARCHIVE_SCORE) continue

            val reason = buildReason(context, idleDays, recent30)
            results += Suggestion(chatId = chatId, score = score, reason = reason)
        }
        return results.sortedByDescending { it.score }.take(MAX_SUGGESTIONS)
    }

    // 8.48：归档原因国际化（此前硬编码中文，英文用户看到中文文案）
    private fun buildReason(context: Context, idleDays: Long, recent30: Int): String =
        when {
            idleDays >= 60 -> context.getString(R.string.ai_archive_reason_idle60, idleDays, recent30)
            idleDays >= 21 -> context.getString(R.string.ai_archive_reason_idle21)
            recent30 <= 2 -> context.getString(R.string.ai_archive_reason_low30)
            else -> context.getString(R.string.ai_archive_reason_decline)
        }

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val MIN_ACTIVE_SILENCE_MS = 14L * 24L * 60L * 60L * 1000L
    private const val CLOSING_SCAN_MESSAGES = 40
    private const val MIN_ARCHIVE_SCORE = 4
    private const val MAX_SUGGESTIONS = 30

    /** 会话收尾信号（已结束/已完成/谢谢/通知类），仅作本地启发式，不构成任何特权声明。 */
    private val CLOSING_HINTS = listOf(
        "谢谢", "感谢", "已结束", "已完成", "搞定", "解决了", "结束",
        "收到", "了解了", "不再", "退群", "暂停", "取消活动", "优惠已过期",
        "thanks", "thank you", "done", "closed", "completed", "resolved"
    )
}
