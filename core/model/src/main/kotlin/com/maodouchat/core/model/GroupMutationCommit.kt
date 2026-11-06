package com.maodouchat.core.model

data class GroupMutationCommit(
    val committed: Boolean = true,
    val refreshedChat: ChatDto? = null,
    val refreshError: Throwable? = null,
    val reconciliationError: Throwable? = null,
) {
    val hasPostCommitWarning: Boolean
        get() = refreshError != null || reconciliationError != null
}
