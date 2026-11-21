package com.maodouchat.ai.agent

import com.maodouchat.domain.messaging.AgentDraftLinePolicy
import com.maodouchat.domain.messaging.AgentReadEmptyPolicy
import com.maodouchat.domain.messaging.AgentSecretGatePolicy
import com.maodouchat.domain.messaging.AgentTaskReadLimitPolicy
import com.maodouchat.domain.messaging.AgentToolPolicy
import com.maodouchat.domain.messaging.AgentWriteErrorPolicy
import com.maodouchat.security.ChatLockSession
import kotlinx.coroutines.flow.first

// Agent 工具执行簇：草稿只读查询。从 AgentTaskReadTools 按读子簇拆出，函数体逐字一致。
internal object AgentTaskDraftReads {
    internal suspend fun listDrafts(ports: AgentToolPorts, userId: String): String {
        val drafts = ports.database.chatDraftDao().observeForOwner(userId).first()
        if (drafts.isEmpty()) return AgentReadEmptyPolicy.noDrafts()
        val locked = ports.database.chatLockDao().listLockedChatIds().toHashSet()
        val secret = ports.database.chatDao().listSecretChatIds().toHashSet()
        val visible = drafts.filter { draft ->
            AgentSecretGatePolicy.includeInChatList(
                isSecret = draft.chatId in secret,
                isLocked = draft.chatId in locked,
                unlocked = ChatLockSession.isUnlocked(draft.chatId)
            )
        }
        if (visible.isEmpty()) return AgentReadEmptyPolicy.noDrafts()
        return visible.take(AgentTaskReadLimitPolicy.MAX_DRAFT_LIST_ITEMS)
            .joinToString("\n") { AgentDraftLinePolicy.draftLine(it.chatId, it.text) }
    }

    internal suspend fun getDraft(ports: AgentToolPorts, userId: String, chatId: String): String {
        AgentMessagingReadGates.denySecretOrLockedChat(ports, chatId)?.let { return it }
        if (chatId.isBlank()) return AgentWriteErrorPolicy.required("chatId")
        val draft = ports.database.chatDraftDao().get(userId, chatId)
        return if (draft == null || draft.text.isBlank()) AgentReadEmptyPolicy.noDraft() else draft.text.take(AgentToolPolicy.MAX_DRAFT_CHARS)
    }
}
