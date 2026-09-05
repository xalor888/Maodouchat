package com.maodouchat.util

import android.content.Context

/**
 * 应用系统通知兼容入口（P07 重构过渡态）。
 *
 * 实现已全部迁入 `com.maodouchat.notification` 下的垂直服务
 *（Message/Call/Social/Reminder）与 [com.maodouchat.notification.NotificationInfrastructure]；
 * 本对象仅保留同签名薄委托与 `EXTRA_*` 入口常量，保证 31 处调用方零改动。
 * `EXTRA_*` 迁移完成后删除本文件（见退役清单）。
 */
object AppNotifier {

    /** 兼容入口：渠道创建已收敛至通知基础设施。 */
    fun ensureChannels(context: Context) =
        com.maodouchat.notification.NotificationInfrastructure.ensureChannels(context)

    fun showMessage(
        context: Context,
        chatId: String,
        senderName: String,
        preview: String,
        messageId: String,
        soundEnabled: Boolean = true,
        expectedUserId: String,
        /** 0.72：群聊消息走独立渠道（独立铃声）。 */
        isGroup: Boolean = false,
    ) = com.maodouchat.notification.MessageNotificationService.showMessage(
        context, chatId, senderName, preview, messageId, soundEnabled, expectedUserId, isGroup
    )

    /**
     * 消息「稍后提醒」到点通知：点击直达聊天并高亮原消息。
     * 复用 messages 渠道；预览同样脱敏（密聊/PIN/全局 App 锁）。
     */
    fun showMessageReminder(
        context: Context,
        chatId: String,
        messageId: String,
        messagePreview: String,
        expectedUserId: String,
    ): Boolean = com.maodouchat.notification.MessageNotificationService.showMessageReminder(
        context, chatId, messageId, messagePreview, expectedUserId
    )

    fun showMissedCall(
        context: Context,
        callId: String,
        callerName: String,
        isVideo: Boolean,
        expectedUserId: String,
    ) = com.maodouchat.notification.CallNotificationService.showMissedCall(
        context, callId, callerName, isVideo, expectedUserId
    )

    fun showIncomingCall(
        context: Context,
        callId: String,
        isVideo: Boolean,
        soundEnabled: Boolean = true,
        senderId: String = "",
        expectedUserId: String,
    ) = com.maodouchat.notification.CallNotificationService.showIncomingCall(
        context, callId, isVideo, soundEnabled, senderId, expectedUserId
    )

    /** 对端已挂断 / 本地已接听或拒绝后清掉系统来电通知，避免幽灵响铃 */
    fun cancelIncomingCall(context: Context, callId: String) =
        com.maodouchat.notification.CallNotificationService.cancelIncomingCall(context, callId)

    /**
     * Missed-call tray uses [NotificationSlotPolicy.missedCallNotifyId] — independent of
     * [NotificationSlotPolicy.incomingCallNotifyId] so endCall / cancelIncoming never
     * erase a just-posted missed entry.
     */
    fun cancelMissedCall(context: Context, callId: String) =
        com.maodouchat.notification.CallNotificationService.cancelMissedCall(context, callId)

    // 槽位分配见 [NotificationSlotPolicy]（来电/未接 id 盐隔离）。

    fun showPostInteraction(
        context: Context,
        postId: String,
        isComment: Boolean,
        soundEnabled: Boolean = true,
        expectedUserId: String,
        // 1.113：互动类型（LIKE / COMMENT / COMMENT_LIKE），用于文案区分
        interaction: String = if (isComment) "COMMENT" else "LIKE",
        // 1.130：评论/回复/评论赞内容预览（非空时用于通知文本与中心 preview）
        preview: String? = null,
        // 1.132：评论 id（打开动态时跳转到该评论）
        commentId: String? = null,
    ) = com.maodouchat.notification.SocialNotificationService.showPostInteraction(
        context, postId, isComment, soundEnabled, expectedUserId, interaction, preview, commentId
    )

    /** Clear post interaction tray (id = `post_{postId}`.hashCode()). */
    fun cancelPostInteraction(context: Context, postId: String) =
        com.maodouchat.notification.SocialNotificationService.cancelPostInteraction(context, postId)

    /** 1.119：设置页「发送测试通知」——用当前通知偏好发一条本地通知验证铃声/震动。 */
    fun showTestNotification(context: Context) =
        com.maodouchat.notification.MessageNotificationService.showTestNotification(context)

    /**
     * Friend-request tray (routing metadata only).
     * Tap → main contacts tab via [EXTRA_OPEN_CONTACTS].
     */
    /** 系统公告通知（高优先级 EMERGENCY/MAINTENANCE）。点击打开 App，详情由公告中心拉取。 */
    fun showAnnouncement(
        context: Context,
        announcementId: String,
        title: String,
        level: String,
        soundEnabled: Boolean = true,
        expectedUserId: String,
    ) = com.maodouchat.notification.SocialNotificationService.showAnnouncement(
        context, announcementId, title, level, soundEnabled, expectedUserId
    )

    fun showFriendRequest(
        context: Context,
        requestId: String,
        action: String,
        soundEnabled: Boolean = true,
        expectedUserId: String,
    ) = com.maodouchat.notification.SocialNotificationService.showFriendRequest(
        context, requestId, action, soundEnabled, expectedUserId
    )

    /**
     * Group-invite tray (routing metadata only).
     * Tap → contacts tab via [EXTRA_OPEN_CONTACTS] (same surface as friend requests).
     */
    fun showGroupInvite(
        context: Context,
        inviteId: String,
        chatId: String,
        action: String,
        soundEnabled: Boolean = true,
        expectedUserId: String,
    ) = com.maodouchat.notification.SocialNotificationService.showGroupInvite(
        context, inviteId, chatId, action, soundEnabled, expectedUserId
    )

    fun showAiTaskReminder(
        context: Context,
        taskId: String,
        chatId: String,
        taskTitle: String,
        dueAt: Long,
        showPreview: Boolean,
        soundEnabled: Boolean,
        expectedUserId: String,
    ): Boolean = com.maodouchat.notification.ReminderNotificationService.showAiTaskReminder(
        context, taskId, chatId, taskTitle, dueAt, showPreview, soundEnabled, expectedUserId
    )

    fun cancelMessage(context: Context, chatId: String) =
        com.maodouchat.notification.MessageNotificationService.cancelMessage(context, chatId)

    fun cancelAiTaskReminder(context: Context, taskId: String) =
        com.maodouchat.notification.ReminderNotificationService.cancelAiTaskReminder(context, taskId)

    /**
     * Drop tray reminders for one chat when the AI tasks screen (or center row) is opened.
     * Notifications are grouped as `ai_tasks_{chatId}` in [showAiTaskReminder].
     */
    fun cancelAiTaskRemindersForChat(context: Context, chatId: String) =
        com.maodouchat.notification.ReminderNotificationService.cancelAiTaskRemindersForChat(context, chatId)

    fun cancelAllAiTaskReminders(context: Context) =
        com.maodouchat.notification.ReminderNotificationService.cancelAllAiTaskReminders(context)

    fun cancelAllFriendRequests(context: Context) =
        com.maodouchat.notification.SocialNotificationService.cancelAllFriendRequests(context)

    fun cancelAllGroupInvites(context: Context) =
        com.maodouchat.notification.SocialNotificationService.cancelAllGroupInvites(context)

    /** Logout / account switch: drop every posted tray notification for this app. */
    fun cancelAll(context: Context) =
        com.maodouchat.notification.NotificationInfrastructure.cancelAll(context)


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

    /**
     * 8.48：定时消息发送失败通知（达重试上限后移除待发条目时提示，避免静默丢失）。
     */
    fun showScheduledMessageFailed(context: Context) =
        com.maodouchat.notification.MessageNotificationService.showScheduledMessageFailed(context)
}
