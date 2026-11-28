package com.maodouchat.ai.agent

import com.maodouchat.domain.messaging.AgentReadGatePolicy
import com.maodouchat.domain.messaging.AgentSecretGatePolicy
import com.maodouchat.domain.messaging.AgentSecretPeerPolicy
import com.maodouchat.security.ChatLockSession

// 密聊/锁定门闩：读簇与写簇共用，先过门闩再碰数据。
internal object AgentMessagingReadGates {
        internal suspend fun requireReadableChat(ports: AgentToolPorts, chatId: String): String? {
            if (chatId.isBlank()) return AgentReadGatePolicy.blankChatIdError()
            val caps = ports.secretConversationController.capabilities(chatId)
            if (!com.maodouchat.domain.messaging.ConversationPrivacyPolicy.allows(caps, com.maodouchat.domain.messaging.PrivacyAction.AI)) {
                return AgentReadGatePolicy.secretChatError()
            }
            return AgentSecretGatePolicy.denyIfSecretOrLocked(
                isSecret = caps.isSecretChat,
                isLocked = caps.isLocked,
                unlocked = ChatLockSession.isUnlocked(chatId)
            )
        }

        internal suspend fun denySecretOrLockedChat(ports: AgentToolPorts, chatId: String): String? =
            requireReadableChat(ports, chatId)

        internal suspend fun blockedChatIds(ports: AgentToolPorts): Set<String> {
            val secret = ports.database.chatDao().listSecretChatIds().toHashSet()
            val locked = ports.database.chatLockDao().listLockedChatIds()
                .filterNot { ChatLockSession.isUnlocked(it) }
                .toHashSet()
            return AgentReadGatePolicy.blockedIds(secret, locked)
        }

        internal suspend fun secretPeerIds(ports: AgentToolPorts): Set<String> =
            AgentSecretPeerPolicy.secretPeerIds(
                ports.database.chatDao().getAllChatsDirect()
                    .filter { AgentSecretPeerPolicy.isSecretChat(it.chatType) }
                    .map { it.participantIds }
            )
}
