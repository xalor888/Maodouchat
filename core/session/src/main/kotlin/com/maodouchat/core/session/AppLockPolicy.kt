package com.maodouchat.core.session

object AppLockPolicy {
    fun shouldLock(
        enabled: Boolean,
        loggedIn: Boolean,
        authenticatedForCurrentUser: Boolean,
        backgroundAtMillis: Long,
        timeoutMillis: Long,
        nowMillis: Long
    ): Boolean {
        if (!enabled || !loggedIn) return false
        if (!authenticatedForCurrentUser) return true
        if (backgroundAtMillis <= 0L) return false
        val elapsed = nowMillis - backgroundAtMillis
        return elapsed < 0L || elapsed >= timeoutMillis.coerceAtLeast(1L)
    }
}
