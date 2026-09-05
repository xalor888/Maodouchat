package com.maodouchat.notification

/**
 * P07 首切片：通知槽位纯策略（纯 Kotlin，无 Android 依赖）。
 *
 * 背景：已删除的 `AppNotifier` 1057 行巨型静态入口内曾散落着各通知的
 * tag/id/分组/data-URI/requestCode 分配——8.44 修过三类通知共用 null-tag id 空间
 * 导致来电被动态互动顶掉的碰撞 bug。本文件把"槽位分配与去重"冻结为唯一事实源：
 * 各通知服务只调用、不再手写字面量；单测锁定槽位字符串与隔离性。
 *
 * 约定（与历史行为逐字一致，改动即破坏已发 PendingIntent/已展示通知的取消路径）：
 * - 会话消息：tag = "maodouchat_<chatId>"，id 固定 0（8.44 前的 hashCode 槽位已废弃）。
 * - 消息稍后提醒：tag = "maodouchat_reminder_<chatId>"，id = messageId.hashCode()。
 * - 来电：tag = "maodouchat_call"，id = callId.hashCode()。
 * - 未接：tag = "maodouchat_missed"，id = callId.hashCode() xor 0x4D495353（"MISS"）。
 * - 动态互动：tag = "maodouchat_post"，id = "post_<postId>".hashCode()。
 * - AI 任务：tag = "maodouchat_ai_task"，id = taskId.hashCode()，
 *   分组 key = "ai_tasks_<chatId>"，summary id = "ai_summary_<chatId>".hashCode()。
 */
object NotificationSlotPolicy {
    const val MESSAGE_TAG_PREFIX = "maodouchat_"
    const val AI_TASK_TAG = "maodouchat_ai_task"
    const val FRIEND_REQUEST_TAG = "maodouchat_friend_request"
    const val GROUP_INVITE_TAG = "maodouchat_group_invite"
    const val ANNOUNCEMENT_TAG = "maodouchat_announcement"
    const val CALL_TAG = "maodouchat_call"
    const val MISSED_CALL_TAG = "maodouchat_missed"
    const val POST_TAG = "maodouchat_post"
    const val TEST_TAG = "maodouchat_test"

    /** 与来电 id 隔离，未接清理永不擦掉已展示的未接条目。 */
    const val MISSED_CALL_ID_SALT = 0x4D495353 // "MISS"

    // ---- 会话消息 ----

    fun messageTag(chatId: String): String = "$MESSAGE_TAG_PREFIX$chatId"

    fun messageNotifyId(): Int = 0

    fun reminderTag(chatId: String): String = "${MESSAGE_TAG_PREFIX}reminder_$chatId"

    fun reminderNotifyId(messageId: String): Int = messageId.hashCode()

    fun reminderRequestCode(chatId: String): Int = "reminder_$chatId".hashCode()

    fun chatRequestCode(chatId: String): Int = chatId.hashCode()

    fun chatDataUri(chatId: String): String = "maodouchat-notify://chat/$chatId"

    fun reminderDataUri(chatId: String, messageId: String): String =
        "maodouchat-notify://reminder/$chatId/$messageId"

    fun quickReplyRequestCode(chatId: String): Int = chatId.hashCode() + 2

    fun quickReplyDataUri(chatId: String): String = "maodouchat-notify-reply://chat/$chatId"

    fun markReadRequestCode(chatId: String): Int = chatId.hashCode() + 1

    fun markReadDataUri(chatId: String): String = "maodouchat-notify-read://chat/$chatId"

    // ---- 通话 ----

    fun incomingCallNotifyId(callId: String): Int = callId.hashCode()

    fun missedCallNotifyId(callId: String): Int = callId.hashCode() xor MISSED_CALL_ID_SALT

    fun incomingCallDataUri(callId: String): String = "maodouchat-notify://incoming/$callId"

    fun missedCallDataUri(callId: String): String = "maodouchat-notify://missed/$callId"

    // ---- 动态互动 ----

    fun postNotifyId(postId: String): Int = "post_$postId".hashCode()

    fun postRequestCode(postId: String): Int = postId.hashCode()

    fun postDataUri(postId: String): String = "maodouchat-notify://post/$postId"

    // ---- AI 任务提醒 ----

    fun aiTaskNotifyId(taskId: String): Int = taskId.hashCode()

    fun aiTaskRequestCode(taskId: String): Int = taskId.hashCode()

    fun aiTaskGroupKey(chatId: String): String = "ai_tasks_$chatId"

    fun aiTaskSummaryId(chatId: String): Int = "ai_summary_$chatId".hashCode()

    fun aiTaskDataUri(taskId: String): String = "maodouchat-notify://aitask/$taskId"

    // ---- 社交（好友/群邀请/公告） ----

    fun friendRequestNotifyId(requestId: String): Int = requestId.hashCode()

    fun friendRequestRequestCode(requestId: String): Int = "friend_$requestId".hashCode()

    fun friendRequestDataUri(requestId: String): String = "maodouchat-notify://friend/$requestId"

    fun groupInviteNotifyId(inviteId: String): Int = inviteId.hashCode()

    fun groupInviteRequestCode(inviteId: String): Int = "group_invite_$inviteId".hashCode()

    fun groupInviteDataUri(inviteId: String): String = "maodouchat-notify://group-invite/$inviteId"

    fun announcementNotifyId(announcementId: String): Int = announcementId.hashCode()

    fun announcementRequestCode(announcementId: String): Int =
        "announcement_$announcementId".hashCode()

    fun announcementDataUri(announcementId: String): String =
        "maodouchat-notify://announcement/$announcementId"

    // ---- 其它 ----

    fun scheduledMessageFailedNotifyId(): Int = "scheduled_message_failed".hashCode()
}
