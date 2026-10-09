package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.repository.AiProfileRepository
import com.maodouchat.network.AiContextMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 周报生成流水线：本周群消息加载 → 消毒 → 本机模型生成结构化周报 → 缓存；有本周缓存直接复用，不重复消耗 AI 预算。原逻辑逐字搬入。
internal object AiWeeklyReportGeneration {
    private const val MAX_REPORT_MESSAGES = 40
    private const val MAX_REPORT_CHARS = 20_000

    suspend fun generate(context: Context, database: AppDatabase, chatId: String): AiWeeklyReport.WeeklyReport? {
        val (weekStart, weekEnd) = AiWeeklyReportWeekRange.currentWeekRange()
        val repository = AiProfileRepository.getInstance(context)
        AiWeeklyReportCache.readCached(repository, chatId, weekStart, weekEnd)?.let { return it }
        if (!AiWeeklyReport.isAllowed(context)) return null

        val messages = withContext(Dispatchers.IO) {
            // 8.48 修复：按会话查询——此前「全库最新 2000 条后按 chatId 过滤」在活跃大库下
            // 目标会话的周消息被其他活跃会话挤掉窗口，周报漏消息/空结果
            database.messageDao()
                .getSearchableMessagesForChat(chatId, limit = 2_000)
                .map { it.toDomain() }
                .filter { it.timestamp in weekStart until weekEnd }
                .takeLast(MAX_REPORT_MESSAGES)
        }
        if (messages.isEmpty()) return null
        val contextMessages = messages.mapNotNull { message ->
            AiPromptSafetyPolicy.sanitizeContextLine(
                sender = message.senderId,
                text = message.parsedContent()
            )?.let { AiContextMessage(it.sender, it.text) }
        }
        if (contextMessages.isEmpty()) return null

        val report = com.maodouchat.ai.agent.LocalAiGateway.groupAssistant(
            context,
            "本周群报",
            contextMessages,
            "summary"
        ).getOrNull()?.trim()?.take(MAX_REPORT_CHARS) ?: return null
        if (report.isBlank()) return null
        val model = com.maodouchat.ai.agent.LocalAiProviderStore.activeProvider(context)?.model
        AiWeeklyReportCache.save(repository, chatId, weekStart, weekEnd, report, model)
        return AiWeeklyReport.WeeklyReport(chatId, weekStart, weekEnd, report, model)
    }
}
