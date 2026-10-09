package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import kotlinx.coroutines.flow.firstOrNull

/** 归档建议打分簇：按静置时长/消息密度/收尾信号给会话打分（纯本地 SQLCipher）。 */
internal object AiArchiveScoring {

    suspend fun compute(context: Context, database: AppDatabase): List<AiArchiveSuggestion.Suggestion> {
        val now = System.currentTimeMillis()
        val chats = database.chatDao().getAllChats().firstOrNull().orEmpty()
        val chatById = chats.associateBy { it.id }

        val results = mutableListOf<AiArchiveSuggestion.Suggestion>()
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

            val reason = AiArchiveReason.buildReason(context, idleDays, recent30)
            results += AiArchiveSuggestion.Suggestion(chatId = chatId, score = score, reason = reason)
        }
        return results.sortedByDescending { it.score }.take(MAX_SUGGESTIONS)
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
