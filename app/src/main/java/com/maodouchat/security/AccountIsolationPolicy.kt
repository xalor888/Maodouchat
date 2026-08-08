package com.maodouchat.security

/**
 * W2-02: pure rules for logout / account-switch data boundaries.
 * Keeps SecureSessionManager free of branching that is hard to unit-test without Android.
 */
enum class AccountSwitchAction {
    /** Same account (or first login with no prior owner) — keep local encrypted store. */
    KEEP_LOCAL_DATA,
    /** Different owner detected — must destroy encrypted DB + cancel workers. */
    PURGE_LOCAL_DATA
}

object AccountIsolationPolicy {
    /**
     * @param previousUserId owner currently bound to local tokens/DB (may be blank after logout)
     * @param nextUserId account about to become active after login
     */
    fun onLoginAccount(previousUserId: String?, nextUserId: String?): AccountSwitchAction {
        val previous = previousUserId?.trim().orEmpty()
        val next = nextUserId?.trim().orEmpty()
        if (next.isEmpty()) return AccountSwitchAction.KEEP_LOCAL_DATA
        if (previous.isEmpty()) return AccountSwitchAction.KEEP_LOCAL_DATA
        return if (previous == next) {
            AccountSwitchAction.KEEP_LOCAL_DATA
        } else {
            AccountSwitchAction.PURGE_LOCAL_DATA
        }
    }

    /** Attachment / AI / draft rows must never run under a mismatched session owner. */
    fun acceptsOwnedRow(sessionUserId: String?, rowOwnerUserId: String?): Boolean {
        val session = sessionUserId?.trim().orEmpty()
        val owner = rowOwnerUserId?.trim().orEmpty()
        if (session.isEmpty() || owner.isEmpty()) return false
        return session == owner
    }

    /** Preference keys must never collide across accounts. */
    fun preferenceKey(base: String, userId: String): String {
        require(base.isNotBlank()) { "preference base required" }
        require(userId.isNotBlank()) { "userId required for scoped preference" }
        return "$base:$userId"
    }

    fun draftPrimaryKey(ownerUserId: String, chatId: String): Pair<String, String> {
        require(ownerUserId.isNotBlank() && chatId.isNotBlank())
        return ownerUserId to chatId
    }
}
