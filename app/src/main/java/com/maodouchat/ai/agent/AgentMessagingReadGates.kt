package com.maodouchat.ai.agent

import com.maodouchat.MaodouchatApp
import com.maodouchat.security.ChatLockSession

// 密聊/锁定门闩：读簇与写簇共用，先过门闩再碰数据。
internal object AgentMessagingReadGates {
        internal suspend fun requireReadableChat(app: MaodouchatApp, chatId: String): String? {
            if (chatId.isBlank()) return "Error: chatId required"
            val caps = app.secretConversationController.capabilities(chatId)
            if (!com.maodouchat.domain.messaging.ConversationPrivacyPolicy.allows(caps, com.maodouchat.domain.messaging.PrivacyAction.AI)) {
                return "Error: secret chats cannot be accessed by AI"
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
            return secret + locked
        }

        internal suspend fun secretPeerIds(app: MaodouchatApp): Set<String> =
            app.database.chatDao().getAllChatsDirect()
                .asSequence()
                .filter { it.chatType == "SECRET" }
                .flatMap { it.participantIds.split(",") }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
}
