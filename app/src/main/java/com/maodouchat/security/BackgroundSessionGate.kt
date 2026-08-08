package com.maodouchat.security

/**
 * Shared mid-batch / mid-op gate for background workers that encrypt or call REST.
 * Abort while local purge is active, after logout clears tokens, or when the local user id
 * no longer matches the batch owner.
 */
object BackgroundSessionGate {
    fun mayContinue(
        expectedUserId: String,
        liveToken: String?,
        liveUserId: String?,
    ): Boolean {
        if (SecureSessionManager.isPurgeInProgress()) return false
        if (expectedUserId.isBlank()) return false
        if (liveToken.isNullOrBlank() || liveUserId.isNullOrBlank()) return false
        return liveUserId == expectedUserId
    }
}
