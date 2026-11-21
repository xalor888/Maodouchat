package com.maodouchat.ai.agent

import com.maodouchat.data.repository.MessageSearchRepository
import com.maodouchat.domain.messaging.AgentMessageLinePolicy
import com.maodouchat.domain.messaging.AgentPinnedLinePolicy
import com.maodouchat.domain.messaging.AgentReadEmptyLinePolicy
import com.maodouchat.domain.messaging.AgentReadGatePolicy
import com.maodouchat.domain.messaging.AgentSecretGatePolicy
import com.maodouchat.domain.messaging.AgentToolHostPolicy
import com.maodouchat.domain.messaging.AiPromptSafetyPolicy
import com.maodouchat.network.ApiService
import com.maodouchat.security.ChatLockSession

// 消息只读查询：搜索、星标、置顶。
internal object AgentMessagingMessageReads {
        internal suspend fun searchMessages(ports: AgentToolPorts, query: String, limit: Int): String {
            val q = AiPromptSafetyPolicy.sanitizeQuery(query)
            if (q.isBlank()) return AgentReadGatePolicy.blankQueryError()
            val hits = MessageSearchRepository(ports.database)
                .search(q, AgentMessageLinePolicy.searchLimit(limit))
            if (hits.isEmpty()) return AgentReadEmptyLinePolicy.NO_MATCHES
            val locked = ports.database.chatLockDao().listLockedChatIds().toHashSet()
            val secret = ports.database.chatDao().listSecretChatIds().toHashSet()
            return hits
                .filter { hit ->
                    AgentSecretGatePolicy.includeInChatList(
                        isSecret = hit.chatId in secret,
                        isLocked = hit.chatId in locked,
                        unlocked = ChatLockSession.isUnlocked(hit.chatId)
                    )
                }
                .joinToString("\n") { hit ->
                    AgentMessageLinePolicy.searchHitLine(hit.chatId, hit.messageId, hit.searchableText)
                }
                .ifBlank { AgentReadEmptyLinePolicy.NO_MATCHES }
        }

        internal suspend fun listStarred(ports: AgentToolPorts, limit: Int): String {
            val rows = ports.database.messageDao().getStarredMessages(AgentMessageLinePolicy.starredLimit(limit))
            if (rows.isEmpty()) return AgentReadEmptyLinePolicy.NO_STARRED_MESSAGES
            val locked = ports.database.chatLockDao().listLockedChatIds().toHashSet()
            val secret = ports.database.chatDao().listSecretChatIds().toHashSet()
            return rows
                .filter { msg ->
                    AgentSecretGatePolicy.includeInChatList(
                        isSecret = msg.chatId in secret,
                        isLocked = msg.chatId in locked,
                        unlocked = ChatLockSession.isUnlocked(msg.chatId)
                    )
                }
                .joinToString("\n") { msg ->
                    AgentMessageLinePolicy.starredLine(msg.chatId, msg.id, msg.content)
                }
                .ifBlank { AgentReadEmptyLinePolicy.NO_STARRED_MESSAGES }
        }

        internal suspend fun listPinned(ports: AgentToolPorts, chatId: String): String {
            AgentMessagingReadGates.denySecretOrLockedChat(ports, chatId)?.let { return it }
            if (chatId.isBlank()) return AgentReadGatePolicy.blankChatIdError()
            val token = ports.token ?: return AgentToolHostPolicy.NOT_SIGNED_IN
            val result = ApiService.getPinnedMessages(token, chatId).getOrElse { return AgentToolHost.fail(it) }
            if (result.pins.isEmpty()) return AgentReadEmptyLinePolicy.NO_PINNED_MESSAGES
            return result.pins.joinToString("\n") { AgentPinnedLinePolicy.pinnedLine(it.messageId, it.pinnedBy, it.pinnedAt) }
        }
}
