package com.maodouchat.core.model

/** 「会话分类」AI 能力的读取端口（G73）。同 `AiWeeklyReportSource` 手法。 */
interface AiChatClassificationSource {
    suspend fun classify(chatId: String): List<CategoryCount>
}
