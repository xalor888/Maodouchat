package com.maodouchat.domain.conversation

import com.maodouchat.core.model.NotificationCenterType

// 通知中心图标种类；图标与颜色留 app 侧，domain 只做类型→种类判定。
enum class NotificationIconKind {
    COMMENT,
    LIKE,
    MISSED_CALL,
    AI_TASK,
    GROUP_INVITE,
    SECURITY,
    MESSAGE,
    FRIEND_REQUEST,
    DEFAULT,
}

fun notificationIconKind(type: String, extraKind: String): NotificationIconKind {
    if (type == NotificationCenterType.POST_INTERACTION) {
        return if (extraKind == "comment" || extraKind == "comment_like") {
            NotificationIconKind.COMMENT
        } else {
            NotificationIconKind.LIKE
        }
    }
    return when (type) {
        NotificationCenterType.MISSED_CALL -> NotificationIconKind.MISSED_CALL
        NotificationCenterType.AI_TASK -> NotificationIconKind.AI_TASK
        NotificationCenterType.GROUP_INVITE -> NotificationIconKind.GROUP_INVITE
        NotificationCenterType.SECURITY -> NotificationIconKind.SECURITY
        NotificationCenterType.MESSAGE -> NotificationIconKind.MESSAGE
        NotificationCenterType.FRIEND_REQUEST -> NotificationIconKind.FRIEND_REQUEST
        else -> NotificationIconKind.DEFAULT
    }
}
