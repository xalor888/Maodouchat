package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Message

/**
 * 会话归档建议打分：按静置时长/消息密度/收尾信号给会话打分（纯本地启发式）。
 * 调用方负责取数（Room 查询仍在 app 侧）与原因文案（需 Context 取本地化字符串）。
 */
object ArchiveSuggestionScoring {

    data class Score(
        val score: Int,
        val idleDays: Long,
        val recent30: Int,
    )

    /**
     * 对单个会话打分；明确不归档（未读/置顶/归档中/14 天内活跃）、无消息或
     * 总分 4 及以下时返回 null（调用方直接跳过该会话）。
     */
    fun score(chat: Chat, messages: List<Message>, nowMs: Long): Score? {
        if (messages.isEmpty()) return null
        if (chat.archived || chat.pinnedAt > 0L || chat.markedUnread || chat.unreadCount > 0) return null
        val lastActive = messages.maxOfOrNull { it.timestamp } ?: chat.lastMessageTime
        if (nowMs - lastActive < MIN_ACTIVE_SILENCE_MS) return null

        var score = 0
        val recent30 = messages.count { nowMs - it.timestamp <= 30L * DAY_MS }
        val idleDays = ((nowMs - lastActive).coerceAtLeast(0L)) / DAY_MS
        score += ((idleDays / 7).coerceAtMost(6) * 2).toInt()          // 静置每 7 天 +2
        score += (30 - recent30.coerceAtMost(30)) / 2          // 近 30 天不活跃 +分

        var closingHints = 0
        var totalHints = 0
        messages.takeLast(CLOSING_SCAN_MESSAGES).forEach { message ->
            val text = message.parsedContent()
            totalHints++
            if (CLOSING_HINTS.any { text.contains(it) }) closingHints++
        }
        if (totalHints > 0) {
            val ratio = closingHints.toDouble() / totalHints
            if (ratio >= 0.4) score += 3
        }
        if (score <= MIN_ARCHIVE_SCORE) return null
        return Score(score, idleDays, recent30)
    }

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val MIN_ACTIVE_SILENCE_MS = 14L * 24L * 60L * 60L * 1000L
    private const val CLOSING_SCAN_MESSAGES = 40
    private const val MIN_ARCHIVE_SCORE = 4

    /** 会话收尾信号（已结束/已完成/谢谢/通知类），仅作本地启发式，不构成任何特权声明。 */
    private val CLOSING_HINTS = listOf(
        "谢谢", "感谢", "已结束", "已完成", "搞定", "解决了", "结束",
        "收到", "了解了", "不再", "退群", "暂停", "取消活动", "优惠已过期",
        "thanks", "thank you", "done", "closed", "completed", "resolved"
    )
}
