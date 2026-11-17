package com.maodouchat.ai

import android.content.Context
import com.maodouchat.core.model.AiPromptSafetyPolicy
import com.maodouchat.core.model.LocalStats
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.core.model.AiContextMessage
import com.maodouchat.domain.messaging.AiProfileNarrativePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 叙事摘要抓取簇：取对方最近消息 → 消毒 → 本地 AI 生成一句叙事摘要。
internal object AiProfileNarrative {

    internal suspend fun fetchNarrative(
        context: Context,
        database: AppDatabase,
        chatId: String,
        stats: LocalStats
    ): String {
        val messages = withContext(Dispatchers.IO) {
            // 9.231：同上按会话查询，仅保留对方消息供叙事摘要
            database.messageDao().getSearchableMessagesForChat(chatId, limit = 200)
                .map { it.toDomain() }
                .filter { it.senderId != selfSenderId() }
                .takeLast(AiProfileNarrativePolicy.MAX_CONTEXT_MESSAGES)
        }
        if (messages.isEmpty()) return ""
        val contextMessages = messages.mapNotNull { message ->
            AiPromptSafetyPolicy.sanitizeContextLine(
                sender = message.senderId,
                text = message.parsedContent()
            )?.let { AiContextMessage(it.sender, it.text) }
        }
        if (contextMessages.isEmpty()) return ""
        val statsHint = AiProfileNarrativePolicy.statsHint(stats)
        return com.maodouchat.ai.agent.LocalAiGateway.summarize(
            context,
            contextMessages,
            "brief"
        ).getOrNull()?.let { AiProfileNarrativePolicy.combineNarrative(statsHint, it) }.orEmpty()
    }

    private fun selfSenderId(): String {
        val tokenManager = com.maodouchat.network.TokenManager.getInstanceOrNull() ?: return ""
        return tokenManager.getUserId().orEmpty()
    }
}
