package com.maodouchat.domain.messaging

// 批量转发结果提示种类；R.string 映射仍在 app 侧，分支顺序与原文逐字一致。
enum class ForwardResultWarningKind {
    FORWARDED,
    SECRET_BLOCKED,
    ATTACHMENT_UPLOAD_FAILED,
    REFRESH_FAILED_CACHED,
}

fun forwardResultWarningKind(
    hasResults: Boolean,
    anyFailed: Boolean,
    anyPinLocked: Boolean,
    anyForbidden: Boolean,
    hasReliableAttachment: Boolean,
): ForwardResultWarningKind =
    when {
        hasResults && !anyFailed -> ForwardResultWarningKind.FORWARDED
        anyPinLocked -> ForwardResultWarningKind.SECRET_BLOCKED
        anyForbidden -> ForwardResultWarningKind.SECRET_BLOCKED
        hasReliableAttachment && anyFailed -> ForwardResultWarningKind.ATTACHMENT_UPLOAD_FAILED
        anyFailed -> ForwardResultWarningKind.REFRESH_FAILED_CACHED
        else -> ForwardResultWarningKind.FORWARDED
    }
