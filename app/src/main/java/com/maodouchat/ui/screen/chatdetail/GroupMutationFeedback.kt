package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind

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

object GroupMutationFeedbackPolicy {
    fun success(action: GroupMutationAction, detail: String? = null) = GroupMutationFeedback(
        kind = GroupMutationFeedbackKind.SUCCESS,
        action = action,
        detail = detail,
        canRetry = false,
        shouldReload = false
    )

    fun fromThrowable(action: GroupMutationAction, error: Throwable?): GroupMutationFeedback {
        val api = error as? ApiException
        val code = api?.serverCode?.trim()?.takeIf { it.isNotEmpty() }
        val status = api?.statusCode
        val detail = api?.serverMessage?.takeIf { it.isNotBlank() }
            ?: error?.message?.takeIf { it.isNotBlank() }

        when (code) {
            // 8.52 契约修复：对齐服务端 GROUP_* 系列（旧 NOT_GROUP_OWNER/FORBIDDEN_ROLE 等从未返回）
            "GROUP_PERMISSION_DENIED",
            "GROUP_ACTOR_NOT_MEMBER",
            "GROUP_OWNER_PROTECTED",
            "GROUP_ADMIN_PEER_PROTECTED",
            "GROUP_MEMBER_BLOCKED" -> {
                return GroupMutationFeedback(
                    kind = GroupMutationFeedbackKind.ERROR_PERMISSION,
                    action = action,
                    detail = detail,
                    serverCode = code,
                    canRetry = false,
                    shouldReload = true
                )
            }
            "GROUP_NOT_FOUND",
            "GROUP_TARGET_NOT_MEMBER",
            "GROUP_USER_NOT_FOUND",
            "GROUP_MEMBER_LIMIT_EXCEEDED",
            "GROUP_OWNER_TRANSFER_REQUIRED" -> {
                return GroupMutationFeedback(
                    kind = GroupMutationFeedbackKind.ERROR_CONFLICT,
                    action = action,
                    detail = detail,
                    serverCode = code,
                    canRetry = false,
                    shouldReload = true
                )
            }
        }

        if (status == 401 || status == 403) {
            return GroupMutationFeedback(
                kind = GroupMutationFeedbackKind.ERROR_PERMISSION,
                action = action,
                detail = detail,
                serverCode = code,
                canRetry = false,
                shouldReload = true
            )
        }
        if (status == 404 || status == 409 || status == 410) {
            return GroupMutationFeedback(
                kind = GroupMutationFeedbackKind.ERROR_CONFLICT,
                action = action,
                detail = detail,
                serverCode = code,
                canRetry = false,
                shouldReload = true
            )
        }
        if (status != null && status in 400..499) {
            return GroupMutationFeedback(
                kind = GroupMutationFeedbackKind.ERROR_FATAL,
                action = action,
                detail = detail,
                serverCode = code,
                canRetry = false,
                shouldReload = false
            )
        }

        val kind = when (api?.kind) {
            ApiFailureKind.TIMEOUT, ApiFailureKind.NETWORK -> GroupMutationFeedbackKind.ERROR_RETRYABLE
            ApiFailureKind.INVALID_RESPONSE -> GroupMutationFeedbackKind.ERROR_RETRYABLE
            ApiFailureKind.HTTP -> if ((status ?: 500) >= 500) {
                GroupMutationFeedbackKind.ERROR_RETRYABLE
            } else {
                GroupMutationFeedbackKind.ERROR_FATAL
            }
            ApiFailureKind.UNEXPECTED, null -> {
                if (error is java.io.IOException) GroupMutationFeedbackKind.ERROR_RETRYABLE
                else GroupMutationFeedbackKind.ERROR_FATAL
            }
        }

        return GroupMutationFeedback(
            kind = kind,
            action = action,
            detail = detail,
            serverCode = code,
            canRetry = kind == GroupMutationFeedbackKind.ERROR_RETRYABLE,
            shouldReload = kind == GroupMutationFeedbackKind.ERROR_PERMISSION ||
                kind == GroupMutationFeedbackKind.ERROR_CONFLICT
        )
    }
}
