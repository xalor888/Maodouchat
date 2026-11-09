package com.maodouchat.ai

import android.content.Context
import com.maodouchat.core.model.ArchiveSuggestion
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.domain.conversation.ArchiveSuggestionScoring
import kotlinx.coroutines.flow.firstOrNull

/** 归档建议取数簇：逐会话查 Room，打分走 :domain:conversation 的纯逻辑。 */
internal object AiArchiveScoring {

    suspend fun compute(context: Context, database: AppDatabase): List<ArchiveSuggestion> {
        val now = System.currentTimeMillis()
        val chats = database.chatDao().getAllChats().firstOrNull().orEmpty()
        val chatById = chats.associateBy { it.id }

        val results = mutableListOf<ArchiveSuggestion>()
        // 8.48 修复：逐会话查询（按 chatId 索引）——此前「全库最新 4000 条后按 chatId 过滤」
        // 在活跃大库下，静置旧会话的历史消息被新会话挤掉窗口，归档建议漏判/失真
        for ((chatId, chat) in chatById) {
            val chatMessages = database.messageDao()
                .getSearchableMessagesForChat(chatId, limit = 4_000)
                .map { it.toDomain() }
            val scored = ArchiveSuggestionScoring.score(chat.toDomain(), chatMessages, now) ?: continue
            val reason = AiArchiveReason.buildReason(context, scored.idleDays, scored.recent30)
            results += ArchiveSuggestion(chatId = chatId, score = scored.score, reason = reason)
        }
        return results.sortedByDescending { it.score }.take(MAX_SUGGESTIONS)
    }

    private const val MAX_SUGGESTIONS = 30
}
