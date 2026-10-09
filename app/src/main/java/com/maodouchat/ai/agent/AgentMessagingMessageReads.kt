package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.ai.AiPromptSafetyPolicy
import com.maodouchat.data.model.Message
import com.maodouchat.data.repository.MessageSearchRepository
import com.maodouchat.network.ApiService
import com.maodouchat.security.ChatLockSession

// 消息只读查询：搜索、星标、置顶。
internal object AgentMessagingMessageReads {
        internal suspend fun searchMessages(app: MaodouchatApp, query: String, limit: Int): String {
            val q = AiPromptSafetyPolicy.sanitizeQuery(query)
            if (q.isBlank()) return "Error: query required"
            val hits = MessageSearchRepository(app.database)
                .search(q, limit.coerceIn(1, AgentToolPolicy.MAX_SEARCH_HITS))
            if (hits.isEmpty()) return "No matches."
            val locked = app.database.chatLockDao().listLockedChatIds().toHashSet()
            val secret = app.database.chatDao().listSecretChatIds().toHashSet()
            return hits
                .filter { hit ->
                    AgentSecretGatePolicy.includeInChatList(
                        isSecret = hit.chatId in secret,
                        isLocked = hit.chatId in locked,
                        unlocked = ChatLockSession.isUnlocked(hit.chatId)
                    )
                }
                .joinToString("\n") { hit ->
                    "${hit.chatId}\t${hit.messageId}\t${hit.searchableText.take(160)}"
                }
                .ifBlank { "No matches." }
        }

        internal fun formatMessage(message: Message): String {
            val text = AiPromptSafetyPolicy.sanitizeContextText(message.parsedContent(), 500)
            return "${message.timestamp}\t${message.senderId}\t${message.type.name}\t$text"
        }

        internal suspend fun listStarred(app: MaodouchatApp, limit: Int): String {
            val rows = app.database.messageDao().getStarredMessages(limit.coerceIn(1, 40))
            if (rows.isEmpty()) return "No starred messages."
            val locked = app.database.chatLockDao().listLockedChatIds().toHashSet()
            val secret = app.database.chatDao().listSecretChatIds().toHashSet()
            return rows
                .filter { msg ->
                    AgentSecretGatePolicy.includeInChatList(
                        isSecret = msg.chatId in secret,
                        isLocked = msg.chatId in locked,
                        unlocked = ChatLockSession.isUnlocked(msg.chatId)
                    )
                }
                .joinToString("\n") { msg ->
                    val text = AiPromptSafetyPolicy.sanitizeContextText(msg.content, 160)
                    "${msg.chatId}\t${msg.id}\t$text"
                }
                .ifBlank { "No starred messages." }
        }

        internal suspend fun listPinned(app: MaodouchatApp, chatId: String): String {
            AgentMessagingReadGates.denySecretOrLockedChat(app, chatId)?.let { return it }
            if (chatId.isBlank()) return "Error: chatId required"
            val token = AgentToolHost.token(app) ?: return "Error: not signed in"
            val result = ApiService.getPinnedMessages(token, chatId).getOrElse { return AgentToolHost.fail(it) }
            if (result.pins.isEmpty()) return "No pinned messages."
            return result.pins.joinToString("\n") { "${it.messageId}\tby=${it.pinnedBy}\tat=${it.pinnedAt}" }
        }
}
