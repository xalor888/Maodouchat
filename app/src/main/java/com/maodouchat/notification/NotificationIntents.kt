package com.maodouchat.notification

/**
 * 通知入口 extras 键（P07 收官：已删除的 `AppNotifier` 逐字迁移）。
 *
 * 值冻结：已发出的 PendingIntent 与系统托盘点击携带的 extras 仍引用这些字符串，
 * 改名即破坏旧通知的跳转与账号归属校验。
 */
object NotificationIntents {
    const val EXTRA_OPEN_CHAT_ID = "maodouchat_open_chat_id"
    /** 消息「稍后提醒」点击：打开聊天后高亮指定消息。 */
    const val EXTRA_OPEN_MESSAGE_ID = "maodouchat_open_message_id"
    const val EXTRA_OPEN_AI_TASKS_CHAT_ID = "maodouchat_open_ai_tasks_chat_id"
    const val EXTRA_OPEN_POST_ID = "maodouchat_open_post_id"
    const val EXTRA_OPEN_MISSED_CALL = "maodouchat_open_missed_call"
    /** Friend-request / contacts deep-link from tray. */
    const val EXTRA_OPEN_CONTACTS = "maodouchat_open_contacts"
    const val EXTRA_NOTIFICATION_OWNER_USER_ID = "maodouchat_notification_owner_user_id"
    /** Tap from FCM/system call notification → open app and poll pending offers. */
    const val EXTRA_OPEN_INCOMING_CALL = "maodouchat_open_incoming_call"
    const val EXTRA_INCOMING_CALL_ID = "maodouchat_incoming_call_id"
    const val EXTRA_INCOMING_CALL_VIDEO = "maodouchat_incoming_call_video"
    const val EXTRA_INCOMING_CALL_SENDER_ID = "maodouchat_incoming_call_sender_id"
}
