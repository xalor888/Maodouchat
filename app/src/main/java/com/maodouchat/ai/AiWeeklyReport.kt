package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.util.RuntimeFlags

/**
 * B4 · 群周报门面（服务端 AiGateway + 本地 SQLCipher 周报缓存）。
 *
 * 客户端只做两件事：
 * 1. 从本地 SQLCipher 库取本周群聊消息，消毒后由用户自配的本机模型生成结构化周报；
 * 2. 结果按 (chatId, weekStart) 缓存进独立 SQLCipher 库，离线可读历史周报。
 *
 * 约束：
 * - 仅群聊（chatType == GROUP / isGroup）；密聊会话跳过；
 * - 走 AiPromptSafetyPolicy 消毒；消息数上限 40（与服务端白名单一致）；
 * - 需 AI 处理同意 + 汇总类能力开关（复用 AI_SUMMARY 网关）。
 *
 * 实现已按簇拆到 AiWeeklyReportWeekRange（时间窗口）、AiWeeklyReportCache（缓存读写）、
 * AiWeeklyReportGeneration（生成流水线），这里只保留统一入口，全部透传，行为不变。
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

    fun currentWeekRange(now: Long = System.currentTimeMillis()): Pair<Long, Long> =
        AiWeeklyReportWeekRange.currentWeekRange(now)

    /**
     * 生成并缓存本周群周报。返回缓存行；失败时返回 null（调用方展示失败态）。
     * 已存在本周缓存且未过期时直接复用，不重复消耗 AI 预算。
     */
    suspend fun generate(context: Context, database: AppDatabase, chatId: String): WeeklyReport? =
        AiWeeklyReportGeneration.generate(context, database, chatId)

    /** 读取某会话的历史周报（仅本地缓存）。 */
    suspend fun history(context: Context, chatId: String, limit: Int = 12): List<WeeklyReport> =
        AiWeeklyReportCache.history(context, chatId, limit)
}
