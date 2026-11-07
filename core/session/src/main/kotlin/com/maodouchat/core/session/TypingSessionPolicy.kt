package com.maodouchat.core.session

/**
 * Whether this process may emit typing start/stop for [ownerUserId].
 * Blocks blank/"me" placeholders and cross-account sessions.
 */
object TypingSessionPolicy {
    fun mayEmit(
        ownerUserId: String?,
        liveToken: String?,
        liveUserId: String?,
        purgeInProgress: Boolean,
    ): Boolean {
        val owner = ownerUserId?.trim().orEmpty()
        if (owner.isBlank() || owner == "me") return false
        return BackgroundSessionGatePolicy.mayContinue(
            expectedUserId = owner,
            liveToken = liveToken,
            liveUserId = liveUserId,
            purgeInProgress = purgeInProgress,
        )
    }

    fun shouldAnnounceStart(activeChatId: String?, alreadyAnnouncedChatId: String?): Boolean {
        val chatId = activeChatId?.takeIf { it.isNotBlank() } ?: return false
        return alreadyAnnouncedChatId != chatId
    }
}
