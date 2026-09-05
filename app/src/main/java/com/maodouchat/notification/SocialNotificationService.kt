package com.maodouchat.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
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
        if (!AppNotifier.notificationOwnerMatches(context, expectedUserId)) return
        AppNotifier.ensureChannels(context)
        if (!AppNotifier.canPostNotifications(context)) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse(NotificationSlotPolicy.announcementDataUri(announcementId))
        }
        with(AppNotifier) { tapIntent.putNotificationOwner(expectedUserId) }
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
        val notification = NotificationCompat.Builder(context, AppNotifier.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_announcement_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(AppNotifier.genericNotification(context, AppNotifier.CHANNEL_MESSAGES, R.string.notification_announcement_title))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!AppNotifier.effectiveSoundEnabled(context, soundEnabled))
            .build()
        if (!AppNotifier.notificationOwnerMatches(context, expectedUserId)) return
        AppNotifier.safeNotify(context, NotificationSlotPolicy.ANNOUNCEMENT_TAG, NotificationSlotPolicy.announcementNotifyId(announcementId), notification, expectedUserId)
    }

    fun showFriendRequest(
        context: Context,
        requestId: String,
        action: String,
        soundEnabled: Boolean = true,
        expectedUserId: String,
    ) {
        if (!AppNotifier.notificationOwnerMatches(context, expectedUserId)) return
        AppNotifier.ensureChannels(context)
        if (!AppNotifier.canPostNotifications(context)) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AppNotifier.EXTRA_OPEN_CONTACTS, true)
            data = Uri.parse(NotificationSlotPolicy.friendRequestDataUri(requestId))
        }
        with(AppNotifier) { tapIntent.putNotificationOwner(expectedUserId) }
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
        val notification = NotificationCompat.Builder(context, AppNotifier.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(bodyRes))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(AppNotifier.genericNotification(context, AppNotifier.CHANNEL_MESSAGES, bodyRes))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!AppNotifier.effectiveSoundEnabled(context, soundEnabled))
            .build()
        if (!AppNotifier.notificationOwnerMatches(context, expectedUserId)) return
        AppNotifier.safeNotify(context, NotificationSlotPolicy.FRIEND_REQUEST_TAG, NotificationSlotPolicy.friendRequestNotifyId(requestId), notification, expectedUserId)
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
        if (!AppNotifier.notificationOwnerMatches(context, expectedUserId)) return
        AppNotifier.ensureChannels(context)
        if (!AppNotifier.canPostNotifications(context)) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AppNotifier.EXTRA_OPEN_CONTACTS, true)
            data = Uri.parse(NotificationSlotPolicy.groupInviteDataUri(inviteId))
        }
        with(AppNotifier) { tapIntent.putNotificationOwner(expectedUserId) }
        val pi = PendingIntent.getActivity(
            context, NotificationSlotPolicy.groupInviteRequestCode(inviteId), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val titleRes = R.string.notification_group_invite
        val bodyRes = R.string.notification_group_invite_body
        val notification = NotificationCompat.Builder(context, AppNotifier.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(bodyRes))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(AppNotifier.genericNotification(context, AppNotifier.CHANNEL_MESSAGES, bodyRes))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!AppNotifier.effectiveSoundEnabled(context, soundEnabled))
            .build()
        if (!AppNotifier.notificationOwnerMatches(context, expectedUserId)) return
        AppNotifier.safeNotify(context, NotificationSlotPolicy.GROUP_INVITE_TAG, NotificationSlotPolicy.groupInviteNotifyId(inviteId), notification, expectedUserId)
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

    fun cancelAllFriendRequests(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
