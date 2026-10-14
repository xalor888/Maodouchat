package com.maodouchat.group

/**
 * W3-01: classifies group-management failures so UI can offer retry vs. reload vs. acknowledge-only.
 * Pure policy — no Android resources — so unit tests stay hermetic.
 */
enum class GroupMutationAction {
    RENAME,
    ANNOUNCEMENT,
    AVATAR,
    INVITE,
    ADD_MEMBER,
    REMOVE_MEMBER,
    ROLE,
    TRANSFER_OWNER,
    TITLE,
    MUTE,
    NICKNAME,
    SENDER_KEY,
    LOAD
}

enum class GroupMutationFeedbackKind {
    SUCCESS,
    ERROR_RETRYABLE,
    ERROR_PERMISSION,
    ERROR_CONFLICT,
    ERROR_FATAL
}

data class GroupMutationFeedback(
    val kind: GroupMutationFeedbackKind,
    val action: GroupMutationAction,
    /** Server / throwable text; UI maps empty to a localized fallback. */
    val detail: String? = null,
    val serverCode: String? = null,
    val canRetry: Boolean = false,
    val shouldReload: Boolean = false
)
