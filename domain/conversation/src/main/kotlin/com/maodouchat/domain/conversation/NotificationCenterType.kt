package com.maodouchat.domain.conversation

/** 通知中心条目类型（字符串常量，服务端与本地共用同一组取值）。 */
object NotificationCenterType {
    const val MESSAGE = "MESSAGE"
    const val MISSED_CALL = "MISSED_CALL"
    const val AI_TASK = "AI_TASK"
    const val POST_INTERACTION = "POST_INTERACTION"
    const val GROUP_INVITE = "GROUP_INVITE"
    const val SECURITY = "SECURITY"
    const val REPORT = "REPORT"
    const val MODERATION = "MODERATION"
    const val FRIEND_REQUEST = "FRIEND_REQUEST"
}
