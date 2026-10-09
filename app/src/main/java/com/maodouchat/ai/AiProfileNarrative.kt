package com.maodouchat.ai

import android.content.Context
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.network.AiContextMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 叙事摘要抓取簇：取对方最近消息 → 消毒 → 本地 AI 生成一句叙事摘要。
internal object AiProfileNarrative {

    internal suspend fun fetchNarrative(
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

    private const val PROFILE_CONTEXT_MESSAGES = 60
    private const val MAX_NARRATIVE_CHARS = 6_000
}
