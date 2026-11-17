package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.core.model.AgentSecretGatePolicy
import com.maodouchat.domain.messaging.AgentReadGatePolicy
import com.maodouchat.domain.messaging.AgentSecretPeerPolicy
import com.maodouchat.security.ChatLockSession

// 密聊/锁定门闩：读簇与写簇共用，先过门闩再碰数据。
internal object AgentMessagingReadGates {
        internal suspend fun requireReadableChat(app: MaodouchatApp, chatId: String): String? {
            if (chatId.isBlank()) return AgentReadGatePolicy.blankChatIdError()
            val caps = app.secretConversationController.capabilities(chatId)
            if (!com.maodouchat.domain.messaging.ConversationPrivacyPolicy.allows(caps, com.maodouchat.domain.messaging.PrivacyAction.AI)) {
                return AgentReadGatePolicy.secretChatError()
            }
            return AgentSecretGatePolicy.denyIfSecretOrLocked(
                isSecret = caps.isSecretChat,
                isLocked = caps.isLocked,
                unlocked = ChatLockSession.isUnlocked(chatId)
            )
        }

        internal suspend fun denySecretOrLockedChat(app: MaodouchatApp, chatId: String): String? =
            requireReadableChat(app, chatId)

        internal suspend fun blockedChatIds(app: MaodouchatApp): Set<String> {
            val secret = app.database.chatDao().listSecretChatIds().toHashSet()
            val locked = app.database.chatLockDao().listLockedChatIds()
                .filterNot { ChatLockSession.isUnlocked(it) }
                .toHashSet()
            return AgentReadGatePolicy.blockedIds(secret, locked)
        }

        internal suspend fun secretPeerIds(app: MaodouchatApp): Set<String> =
            AgentSecretPeerPolicy.secretPeerIds(
                app.database.chatDao().getAllChatsDirect()
                    .filter { it.chatType == "SECRET" }
                    .map { it.participantIds }
            )
}
