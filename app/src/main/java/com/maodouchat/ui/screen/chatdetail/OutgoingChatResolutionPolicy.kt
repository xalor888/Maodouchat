package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Chat

/** Pure decisions used while chat metadata is still loading. */
internal object OutgoingChatResolutionPolicy {
    fun knownChatId(activeChatId: String, constructorChatId: String, loadedChatId: String?): String =
        activeChatId.takeIf { it.isNotBlank() }
            ?: constructorChatId.takeIf { it.isNotBlank() }
            ?: loadedChatId.orEmpty().takeIf { it.isNotBlank() }
            ?: ""

    fun directPeerId(chat: Chat, selfUserId: String, activeContactId: String?): String? {
        if (chat.isGroup) return null
        return chat.participants
            .asSequence()
            .map { it.id }
            .firstOrNull { it.isNotBlank() && it != selfUserId }
            ?: activeContactId?.takeIf { it.isNotBlank() && it != selfUserId && it != "me" }
    }
}
