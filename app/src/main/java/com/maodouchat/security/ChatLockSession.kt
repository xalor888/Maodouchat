package com.maodouchat.security

import com.maodouchat.network.TokenManager

/**
 * Process-scoped unlock cache for per-chat PIN locks.
 * Survives navigation (e.g. chat detail → media center) but not process death.
 * Bound to the live account so a missed purge cannot carry unlocks across users.
 */
object ChatLockPolicy {
    fun isUnlocked(
        chatId: String,
        unlocked: Set<String>,
        ownerUserId: String?,
        liveUserId: String?
    ): Boolean {
        if (chatId.isBlank()) return false
        if (ownerUserId != null && ownerUserId != liveUserId) return false
        return chatId in unlocked
    }
}

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
