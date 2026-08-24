package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.repository.AiProfileRepository
import com.maodouchat.network.AiContextMessage
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * B4 · 群周报（服务端 AiGateway + 本地 SQLCipher 周报缓存）。
 *
 * 客户端只做两件事：
 * 1. 从本地 SQLCipher 库取本周群聊消息，消毒后由用户自配的本机模型生成结构化周报；
 * 2. 结果按 (chatId, weekStart) 缓存进独立 SQLCipher 库，离线可读历史周报。
 *
 * 约束：
 * - 仅群聊（chatType == GROUP / isGroup）；密聊会话跳过；
 * - 走 AiPromptSafetyPolicy 消毒；消息数上限 40（与服务端白名单一致）；
 * - 需 AI 处理同意 + 汇总类能力开关（复用 AI_SUMMARY 网关）。
 */
object AiWeeklyReport {

    fun isAllowed(context: Context): Boolean =
        AiPrivacyPreferences.mayUploadCloudContext(context) &&
            RuntimeFlags.isEnabled(context, RuntimeFlags.AI_MASTER)

    data class WeeklyReport(
        val chatId: String,
        val weekStart: Long,
        val weekEnd: Long,
        val report: String,
        val model: String? = null,
        val createdAt: Long = System.currentTimeMillis()
    )

    /** 本周起点（周一 00:00）与终点（下周一 00:00），按本地时区。 */
    fun currentWeekRange(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val start = calendar.timeInMillis
        return start to start + 7L * 24L * 60L * 60L * 1000L
    }

    /**
     * 生成并缓存本周群周报。返回缓存行；失败时返回 null（调用方展示失败态）。
     * 已存在本周缓存且未过期时直接复用，不重复消耗 AI 预算。
     */
    suspend fun generate(context: Context, database: AppDatabase, chatId: String): WeeklyReport? {
        val (weekStart, weekEnd) = currentWeekRange()
        val repository = AiProfileRepository.getInstance(context)
        val cachedRow = withContext(Dispatchers.IO) { repository.getWeeklyReport(chatId, weekStart) }
        if (cachedRow != null) {
            return WeeklyReport(chatId, weekStart, weekEnd, cachedRow.report, cachedRow.model, cachedRow.createdAt)
        }
        if (!isAllowed(context)) return null

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
        withContext(Dispatchers.IO) {
            repository.saveWeeklyReport(chatId, weekStart, weekEnd, report, model)
        }
        return WeeklyReport(chatId, weekStart, weekEnd, report, model)
    }

    /** 读取某会话的历史周报（仅本地缓存）。 */
    suspend fun history(context: Context, chatId: String, limit: Int = 12): List<WeeklyReport> =
        withContext(Dispatchers.IO) {
            AiProfileRepository.getInstance(context).listWeeklyReports(chatId, limit).map { row ->
                WeeklyReport(row.chatId, row.weekStart, row.weekEnd, row.report, row.model, row.createdAt)
            }
        }

    private const val MAX_REPORT_MESSAGES = 40
    private const val MAX_REPORT_CHARS = 20_000
}
