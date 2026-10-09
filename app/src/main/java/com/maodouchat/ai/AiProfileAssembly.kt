package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.repository.AiProfileRepository
import com.maodouchat.network.AiContextMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

// 会话画像装配：本地统计画像生成 + 上次缓存读取 + 服务端叙事摘要（失败不阻断本地结果）。
// 密聊会话不参与（结果不应落可搜索缓存）；明文只存在于本机 SQLCipher 解密通道。
internal object AiProfileAssembly {
    /**
     * 生成本地统计画像；若允许且开关开启，再从服务端取叙事摘要（失败不阻断本地结果）。
     * 读取消息在 [Dispatchers.IO] 内进行，明文只存在于本机 SQLCipher 解密通道。
     */
    suspend fun build(
        context: Context,
        database: AppDatabase,
        chatId: String
    ): AiConversationProfile.ConversationProfile {
        val stats = withContext(Dispatchers.IO) {
            // 9.231：按会话查询——此前「全库最新 800 条后 filter chatId」在活跃大库下
            // 目标会话不在最新窗口时统计失真（同 8.48 分类器修复）
            val messages = database.messageDao().getSearchableMessagesForChat(chatId, limit = 800)
                .map { it.toDomain() }
            AiProfileStats.computeStats(messages)
        }
        val narrative = if (AiProfileGates.isAllowed(context) && stats.messageCount > 0) {
            try {
                fetchNarrative(context, database, chatId, stats)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val profile = AiConversationProfile.ConversationProfile(chatId = chatId, local = stats, narrative = narrative)
        withContext(Dispatchers.IO) {
            AiProfileRepository.getInstance(context).saveProfile(
                chatId = chatId,
                statsJson = json.encodeToString(AiConversationProfile.LocalStats.serializer(), stats),
                narrative = narrative
            )
        }
        return profile
    }

    /** 读取上次画像缓存（仅本地，不发请求）。 */
    suspend fun cached(
        context: Context,
        database: AppDatabase,
        chatId: String
    ): AiConversationProfile.ConversationProfile? {
        val repository = AiProfileRepository.getInstance(context)
        val row = withContext(Dispatchers.IO) { repository.getProfile(chatId) } ?: return null
        val stats = runCatching {
            json.decodeFromString(AiConversationProfile.LocalStats.serializer(), row.statsJson)
        }.getOrElse { AiConversationProfile.LocalStats() }
        return AiConversationProfile.ConversationProfile(chatId, stats, row.narrative, row.updatedAt)
    }

    private suspend fun fetchNarrative(
        context: Context,
        database: AppDatabase,
        chatId: String,
        stats: AiConversationProfile.LocalStats
    ): String {
        val messages = withContext(Dispatchers.IO) {
            // 9.231：同上按会话查询，仅保留对方消息供叙事摘要
            database.messageDao().getSearchableMessagesForChat(chatId, limit = 200)
                .map { it.toDomain() }
                .filter { it.senderId != selfSenderId() }
                .takeLast(PROFILE_CONTEXT_MESSAGES)
        }
        if (messages.isEmpty()) return ""
        val contextMessages = messages.mapNotNull { message ->
            AiPromptSafetyPolicy.sanitizeContextLine(
                sender = message.senderId,
                text = message.parsedContent()
            )?.let { AiContextMessage(it.sender, it.text) }
        }
        if (contextMessages.isEmpty()) return ""
        val statsHint = "消息 ${stats.messageCount} 条，活跃 ${stats.activeDays} 天，主题 ${stats.topTerms.take(8).joinToString("、")}"
        return com.maodouchat.ai.agent.LocalAiGateway.summarize(
            context,
            contextMessages,
            "brief"
        ).getOrNull()?.let { "$statsHint。$it" }?.trim()?.take(MAX_NARRATIVE_CHARS).orEmpty()
    }

    private fun selfSenderId(): String {
        val tokenManager = com.maodouchat.network.TokenManager.getInstanceOrNull() ?: return ""
        return tokenManager.getUserId().orEmpty()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private const val PROFILE_CONTEXT_MESSAGES = 60
    private const val MAX_NARRATIVE_CHARS = 6_000
}
