package com.maodouchat.security

/**
 * Process-scoped unlock cache for per-chat PIN locks.
 * Survives navigation (e.g. chat detail → media center) but not process death.
 */
object ChatLockSession {
    private val unlocked = mutableSetOf<String>()

    @Synchronized
    fun isUnlocked(chatId: String): Boolean {
        if (chatId.isBlank()) return true
        return chatId in unlocked
    }

    @Synchronized
    fun markUnlocked(chatId: String) {
        if (chatId.isBlank()) return
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
    }
}
