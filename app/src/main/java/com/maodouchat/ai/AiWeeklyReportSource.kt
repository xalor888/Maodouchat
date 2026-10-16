package com.maodouchat.ai


/**
 * 「周报」AI 能力的读取端口（G73）。同 `AiConversationProfileSource` 手法。
 */
interface AiWeeklyReportSource {
    suspend fun generate(chatId: String): WeeklyReport?
}
