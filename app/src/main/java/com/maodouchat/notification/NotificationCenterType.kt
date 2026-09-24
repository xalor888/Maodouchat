package com.maodouchat.notification

/**
 * 通知中心的条目类型（字符串常量，服务端与本地共用同一组取值）。
 *
 * G328c：原先它定义在 `ui/screen/chatlist/NotificationCenterScreen.kt` 文件末尾，于是
 * `notification/` 下 5 个服务为了这个常量被迫 import `com.maodouchat.ui.*`——
 * 通知服务依赖 UI 包是明确的分层倒置（审计点名的 6 处之一）。抽到 notification 包后，
 * 方向恢复为 ui/notification → notification，而不是反过来。
 */
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
