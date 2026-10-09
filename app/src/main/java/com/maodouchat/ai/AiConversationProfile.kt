package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import kotlinx.serialization.Serializable

/**
 * B4 · 会话画像（本地 SQLCipher 优先 + 服务端 AiGateway 叙事增强）。
 *
 * 本地画像：从解密后的本地消息（SQLCipher 库内读取）统计
 * - 消息量 / 活跃天数 / 时段分布（早午晚）
 * - 表情使用
 * - 高频主题词（CJK 二元组 + 拉丁词，本地轻量统计，不引入 embedding）
 *
 * 叙事：用户自配的本机模型根据已解密上下文生成一段「对端画像/话题画像」，缓存到本地。
 *
 * 约束：
 * - 走 AiPromptSafetyPolicy 消毒（控制符剥离、截断、标注不可信）；
 * - 密聊会话不参与（结果不应落可搜索缓存）；
 * - 需已同意 AI 处理 + 本地画像开关（默认开）。
 *
 * 实现已按簇拆到 AiProfileGates / AiProfileAssembly / AiProfileStats，
 * 这里只保留统一入口与嵌套类型（外部端口与测试按此引用）。
 */
object AiConversationProfile {
    @Serializable
    data class LocalStats(
        val messageCount: Int = 0,
        val activeDays: Int = 0,
        val morning: Int = 0,
        val afternoon: Int = 0,
        val evening: Int = 0,
        val night: Int = 0,
        val emojiCount: Int = 0,
        val topTerms: List<String> = emptyList()
    )

    data class ConversationProfile(
        val chatId: String,
        val local: LocalStats,
        val narrative: String? = null,
        val updatedAt: Long = System.currentTimeMillis()
    )

    /** 本地画像开关（与 AI 处理同意独立，默认开，纯本机）。 */
    fun isAllowed(context: Context): Boolean =
        AiProfileGates.isAllowed(context)

    fun isLocalProfileEnabled(context: Context): Boolean =
        AiProfileGates.isLocalProfileEnabled(context)

    suspend fun build(context: Context, database: AppDatabase, chatId: String): ConversationProfile =
        AiProfileAssembly.build(context, database, chatId)

    /** 读取上次画像缓存（仅本地，不发请求）。 */
    suspend fun cached(context: Context, database: AppDatabase, chatId: String): ConversationProfile? =
        AiProfileAssembly.cached(context, database, chatId)
}
