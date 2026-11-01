package com.maodouchat.core.session

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
