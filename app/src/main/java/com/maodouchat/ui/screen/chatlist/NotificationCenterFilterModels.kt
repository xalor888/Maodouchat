package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.notification.NotificationCenterType

internal enum class NotifFilter {
    ALL,
    UNREAD,
    MESSAGE,
    MISSED_CALL,
    AI_TASK,
    POST_INTERACTION,
    FRIEND_REQUEST;

    fun matches(item: NotificationCenterItem): Boolean = when (this) {
        ALL -> true
        UNREAD -> !item.read
        MESSAGE -> item.type == NotificationCenterType.MESSAGE
        MISSED_CALL -> item.type == NotificationCenterType.MISSED_CALL
        AI_TASK -> item.type == NotificationCenterType.AI_TASK
        POST_INTERACTION -> item.type == NotificationCenterType.POST_INTERACTION
        FRIEND_REQUEST -> item.type == NotificationCenterType.FRIEND_REQUEST
    }
}
