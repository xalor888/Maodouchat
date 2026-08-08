package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.security.BackgroundSessionGate

/**
 * Whether this process may emit typing start/stop for [ownerUserId].
 * Blocks blank/"me" placeholders and cross-account sessions.
 */
object TypingSessionPolicy {
    fun mayEmit(
        ownerUserId: String?,
        liveToken: String?,
        liveUserId: String?
    ): Boolean {
        val owner = ownerUserId?.trim().orEmpty()
        if (owner.isBlank() || owner == "me") return false
        return BackgroundSessionGate.mayContinue(
            expectedUserId = owner,
            liveToken = liveToken,
            liveUserId = liveUserId
        )
    }

    fun shouldAnnounceStart(activeChatId: String?, alreadyAnnouncedChatId: String?): Boolean {
        val chatId = activeChatId?.takeIf { it.isNotBlank() } ?: return false
        return alreadyAnnouncedChatId != chatId
    }
}
