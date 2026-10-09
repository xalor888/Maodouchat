package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.repository.AiProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 周报本地缓存：按 (chatId, weekStart) 读写独立 SQLCipher 库，离线可读历史周报。
internal object AiWeeklyReportCache {
    suspend fun readCached(
        repository: AiProfileRepository,
        chatId: String,
        weekStart: Long,
        weekEnd: Long
    ): AiWeeklyReport.WeeklyReport? =
        withContext(Dispatchers.IO) {
            repository.getWeeklyReport(chatId, weekStart)?.let { row ->
                AiWeeklyReport.WeeklyReport(chatId, weekStart, weekEnd, row.report, row.model, row.createdAt)
            }
        }

    suspend fun save(
        repository: AiProfileRepository,
        chatId: String,
        weekStart: Long,
        weekEnd: Long,
        report: String,
        model: String?
    ) {
        withContext(Dispatchers.IO) {
            repository.saveWeeklyReport(chatId, weekStart, weekEnd, report, model)
        }
    }

    /** 读取某会话的历史周报（仅本地缓存）。 */
    suspend fun history(
        context: Context,
        chatId: String,
        limit: Int = 12
    ): List<AiWeeklyReport.WeeklyReport> =
        withContext(Dispatchers.IO) {
            AiProfileRepository.getInstance(context).listWeeklyReports(chatId, limit).map { row ->
                AiWeeklyReport.WeeklyReport(row.chatId, row.weekStart, row.weekEnd, row.report, row.model, row.createdAt)
            }
        }
}
