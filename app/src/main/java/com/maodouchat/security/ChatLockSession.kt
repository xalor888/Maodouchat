package com.maodouchat.security

import com.maodouchat.core.session.ChatLockPolicy
import com.maodouchat.network.TokenManager

object ChatLockSession {
    private val unlocked = mutableSetOf<String>()
    @Volatile
    private var ownerUserId: String? = null

    @Synchronized
    fun isUnlocked(chatId: String): Boolean =
        ChatLockPolicy.isUnlocked(chatId, unlocked, ownerUserId, liveUserId())

    @Synchronized
    fun markUnlocked(chatId: String) {
        if (chatId.isBlank()) return
        val live = liveUserId()
        if (ownerUserId != null && live != null && ownerUserId != live) {
            unlocked.clear()
        }
        if (live != null) ownerUserId = live
        unlocked.add(chatId)
    }

    @Synchronized
    fun clear(chatId: String) {
        if (chatId.isBlank()) return
        unlocked.remove(chatId)
    }

    @Synchronized
    fun clearAll() {
        unlocked.clear()
        ownerUserId = null
    }

    private fun liveUserId(): String? =
        TokenManager.getInstanceOrNull()?.getUserId()?.takeIf { it.isNotBlank() }
}
