package com.maodouchat.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.maodouchat.MainActivity
import com.maodouchat.R
import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.ui.screen.chatlist.NotificationCenterType
import com.maodouchat.util.AppNotifier

/**
 * P07 服务拆分 3/4：Social（公告/好友申请/群邀请）通知服务。
 *
 * 从巨型 [AppNotifier] 静态入口迁出的第三个垂直服务；`AppNotifier` 保留同签名
 * 薄委托。社交类通知点击均进入联系人 Tab（[AppNotifier.EXTRA_OPEN_CONTACTS]），
 * 正文均为固定文案、无隐私脱敏分支（与消息/通话类不同），行为逐行搬运。
 */
object SocialNotificationService {

    /** 系统公告通知（高优先级 EMERGENCY/MAINTENANCE）。点击打开 App，详情由公告中心拉取。 */
    fun showAnnouncement(
        context: Context,
        announcementId: String,
        title: String,
        level: String,
        soundEnabled: Boolean = true,
        expectedUserId: String,
    ) {
        if (!NotificationInfrastructure.notificationOwnerMatches(context, expectedUserId)) return
        NotificationInfrastructure.ensureChannels(context)
        if (!NotificationInfrastructure.canPostNotifications(context)) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse(NotificationSlotPolicy.announcementDataUri(announcementId))
        }
        with(NotificationInfrastructure) { tapIntent.putNotificationOwner(expectedUserId) }
        val pi = PendingIntent.getActivity(
            context, NotificationSlotPolicy.announcementRequestCode(announcementId), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val levelLabel = when (level) {
            "EMERGENCY" -> context.getString(R.string.announcement_level_emergency)
            "MAINTENANCE" -> context.getString(R.string.announcement_level_maintenance)
            else -> context.getString(R.string.announcement_level_info)
        }
        val body = "$levelLabel · $title"
        val notification = NotificationCompat.Builder(context, NotificationInfrastructure.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_announcement_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationInfrastructure.genericNotification(context, NotificationInfrastructure.CHANNEL_MESSAGES, R.string.notification_announcement_title))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!NotificationInfrastructure.effectiveSoundEnabled(context, soundEnabled))
            .build()
        if (!NotificationInfrastructure.notificationOwnerMatches(context, expectedUserId)) return
        NotificationInfrastructure.safeNotify(context, NotificationSlotPolicy.ANNOUNCEMENT_TAG, NotificationSlotPolicy.announcementNotifyId(announcementId), notification, expectedUserId)
    }

    fun showFriendRequest(
        context: Context,
        requestId: String,
        action: String,
        soundEnabled: Boolean = true,
        expectedUserId: String,
    ) {
        if (!NotificationInfrastructure.notificationOwnerMatches(context, expectedUserId)) return
        NotificationInfrastructure.ensureChannels(context)
        if (!NotificationInfrastructure.canPostNotifications(context)) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AppNotifier.EXTRA_OPEN_CONTACTS, true)
            data = Uri.parse(NotificationSlotPolicy.friendRequestDataUri(requestId))
        }
        with(NotificationInfrastructure) { tapIntent.putNotificationOwner(expectedUserId) }
        val pi = PendingIntent.getActivity(
            context, NotificationSlotPolicy.friendRequestRequestCode(requestId), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val titleRes = if (action == "ACCEPTED") {
            R.string.notification_friend_accepted
        } else {
            R.string.notification_friend_request
        }
        val bodyRes = if (action == "ACCEPTED") {
            R.string.notification_friend_accepted_body
        } else {
            R.string.notification_friend_request_body
        }
        val notification = NotificationCompat.Builder(context, NotificationInfrastructure.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(bodyRes))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationInfrastructure.genericNotification(context, NotificationInfrastructure.CHANNEL_MESSAGES, bodyRes))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!NotificationInfrastructure.effectiveSoundEnabled(context, soundEnabled))
            .build()
        if (!NotificationInfrastructure.notificationOwnerMatches(context, expectedUserId)) return
        NotificationInfrastructure.safeNotify(context, NotificationSlotPolicy.FRIEND_REQUEST_TAG, NotificationSlotPolicy.friendRequestNotifyId(requestId), notification, expectedUserId)
        runCatching {
            com.maodouchat.MaodouchatApp.emitNotificationCenterItem(
                NotificationCenterItem(
                    id = "friend_push_${requestId}_$action",
                    type = NotificationCenterType.FRIEND_REQUEST,
                    mergeKey = "friend_request",
                    title = context.getString(titleRes),
                    subtitle = context.getString(bodyRes),
                    preview = null,
                    deeplink = "maodouchat:contacts",
                    extra = mapOf("requestId" to requestId, "action" to action)
                ),
                expectedUserId = expectedUserId,
            )
        }
    }

    /**
     * Group-invite tray (routing metadata only).
     * Tap → contacts tab via [AppNotifier.EXTRA_OPEN_CONTACTS] (same surface as friend requests).
     */
    fun showGroupInvite(
        context: Context,
        inviteId: String,
        chatId: String,
        action: String,
        soundEnabled: Boolean = true,
        expectedUserId: String,
    ) {
        if (action != "CREATED") return
        if (!NotificationInfrastructure.notificationOwnerMatches(context, expectedUserId)) return
        NotificationInfrastructure.ensureChannels(context)
        if (!NotificationInfrastructure.canPostNotifications(context)) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AppNotifier.EXTRA_OPEN_CONTACTS, true)
            data = Uri.parse(NotificationSlotPolicy.groupInviteDataUri(inviteId))
        }
        with(NotificationInfrastructure) { tapIntent.putNotificationOwner(expectedUserId) }
        val pi = PendingIntent.getActivity(
            context, NotificationSlotPolicy.groupInviteRequestCode(inviteId), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val titleRes = R.string.notification_group_invite
        val bodyRes = R.string.notification_group_invite_body
        val notification = NotificationCompat.Builder(context, NotificationInfrastructure.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(bodyRes))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationInfrastructure.genericNotification(context, NotificationInfrastructure.CHANNEL_MESSAGES, bodyRes))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!NotificationInfrastructure.effectiveSoundEnabled(context, soundEnabled))
            .build()
        if (!NotificationInfrastructure.notificationOwnerMatches(context, expectedUserId)) return
        NotificationInfrastructure.safeNotify(context, NotificationSlotPolicy.GROUP_INVITE_TAG, NotificationSlotPolicy.groupInviteNotifyId(inviteId), notification, expectedUserId)
        runCatching {
            com.maodouchat.MaodouchatApp.emitNotificationCenterItem(
                NotificationCenterItem(
                    id = "group_invite_push_${inviteId}_$action",
                    type = NotificationCenterType.GROUP_INVITE,
                    mergeKey = "group_invite",
                    title = context.getString(titleRes),
                    subtitle = context.getString(bodyRes),
                    preview = null,
                    deeplink = "maodouchat:group_invites",
                    extra = mapOf("inviteId" to inviteId, "chatId" to chatId, "action" to action)
                ),
                expectedUserId = expectedUserId,
            )
        }
    }

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
    ) {
        if (!NotificationInfrastructure.notificationOwnerMatches(context, expectedUserId)) return
        NotificationInfrastructure.ensureChannels(context)
        if (!NotificationInfrastructure.canPostNotifications(context)) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AppNotifier.EXTRA_OPEN_POST_ID, postId)
            data = Uri.parse(NotificationSlotPolicy.postDataUri(postId))
        }
        with(NotificationInfrastructure) { tapIntent.putNotificationOwner(expectedUserId) }
        val pi = PendingIntent.getActivity(
            context, NotificationSlotPolicy.postRequestCode(postId), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 1.113：评论被赞 → 独立文案；1.122：回复 → 独立文案
        val textRes = when (interaction) {
            "COMMENT_LIKE" -> R.string.notification_post_comment_like
            "REPLY" -> R.string.notification_post_reply
            "COMMENT" -> R.string.notification_post_comment
            else -> R.string.notification_post_like
        }
        val baseText = context.getString(textRes)
        // 9.137：互动通知预览与 showMessage 同口径脱敏——App 锁/隐藏通知内容开启时，
        // 锁屏与通知中心不得明文展示评论/回复正文（此前是唯一漏掉该检查的消息类通知路径）
        val hideDetails = NotificationInfrastructure.shouldHideSensitiveDetails(context)
        // 1.130：有内容预览时追加到文案（通知栏一行）
        val contentText = if (hideDetails) baseText
        else preview?.takeIf(String::isNotBlank)?.let { "$baseText：$it" } ?: baseText
        val notification = NotificationCompat.Builder(context, NotificationInfrastructure.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_post_interaction))
            .setContentText(contentText)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationInfrastructure.genericNotification(context, NotificationInfrastructure.CHANNEL_MESSAGES, R.string.notification_post_interaction))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!NotificationInfrastructure.effectiveSoundEnabled(context, soundEnabled))
            .build()
        if (!NotificationInfrastructure.notificationOwnerMatches(context, expectedUserId)) return
        // 8.44：动态互动通知独立 tag
        NotificationInfrastructure.safeNotify(context, NotificationSlotPolicy.POST_TAG, NotificationSlotPolicy.postNotifyId(postId), notification, expectedUserId)
        // 同步到通知中心
        runCatching {
            com.maodouchat.MaodouchatApp.emitNotificationCenterItem(
                NotificationCenterItem(
                    id = "post_${postId}_${if (interaction == "COMMENT_LIKE") "cl" else if (isComment) "c" else "l"}",
                    type = NotificationCenterType.POST_INTERACTION,
                    mergeKey = "post_$postId",
                    title = context.getString(R.string.notification_post_interaction),
                    subtitle = context.getString(textRes),
                    // 9.137：脱敏时通知中心同样不存评论/回复明文
                    preview = if (hideDetails) null else preview?.takeIf(String::isNotBlank),
                    // 1.132：评论 id 供详情页跳转
                    deeplink = if (commentId.isNullOrBlank()) "maodouchat:post:$postId" else "maodouchat:post:$postId?comment=${java.net.URLEncoder.encode(commentId, Charsets.UTF_8.name())}",
                    extra = mapOf(
                        "postId" to postId,
                        "kind" to if (interaction == "COMMENT_LIKE") "comment_like" else if (isComment) "comment" else "like",
                        "commentId" to (commentId ?: "")
                    )
                ),
                expectedUserId = expectedUserId,
            )
        }
    }

    /** Clear post interaction tray (id = `post_{postId}`.hashCode()). */
    fun cancelPostInteraction(context: Context, postId: String) {
        if (postId.isBlank()) return
        NotificationManagerCompat.from(context).cancel(NotificationSlotPolicy.POST_TAG, NotificationSlotPolicy.postNotifyId(postId))
    }

    fun cancelAllFriendRequests(context: Context) {        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications
            .filter { it.tag == NotificationSlotPolicy.FRIEND_REQUEST_TAG }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    fun cancelAllGroupInvites(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications
            .filter { it.tag == NotificationSlotPolicy.GROUP_INVITE_TAG }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}
