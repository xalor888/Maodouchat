package com.maodouchat.data.local

import com.maodouchat.ai.AiWeeklyReport
import com.maodouchat.ai.AiWeeklyReportSource

/** `AiWeeklyReportSource` 的装配实现（G73）。 */
internal class RoomAiWeeklyReportSource(
    private val context: android.content.Context,
    private val database: AppDatabase,
) : AiWeeklyReportSource {
    override suspend fun generate(chatId: String): AiWeeklyReport.WeeklyReport? =
        AiWeeklyReport.generate(context, database, chatId)
}
