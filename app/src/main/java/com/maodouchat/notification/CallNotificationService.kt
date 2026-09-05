package com.maodouchat.notification

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
 * P07 服务拆分 2/4：Call（来电/未接）通知服务。
 *
 * 从巨型 [AppNotifier] 静态入口迁出的第二个垂直服务；`AppNotifier` 保留同签名
 * 薄委托，现有调用方（通话信令、前台服务、Telecom 路径）零改动。来电 35s 超时、
 * 全屏 intent、ongoing 标记与未接盐隔离 id 等行为逐行搬运（见 NotificationSlotPolicy）。
 */
object CallNotificationService {

    fun showMissedCall(
        context: Context,
        callId: String,
        callerName: String,
        isVideo: Boolean,
        expectedUserId: String,
    ) {
        if (!AppNotifier.notificationOwnerMatches(context, expectedUserId)) return
        AppNotifier.ensureChannels(context)
        if (!AppNotifier.canPostNotifications(context)) return
        // Always drop the ringing tray first; ids are intentionally distinct so a later
        // cancelIncomingCall cannot erase this missed entry.
        cancelIncomingCall(context, callId)
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AppNotifier.EXTRA_OPEN_MISSED_CALL, true)
            data = Uri.parse(NotificationSlotPolicy.missedCallDataUri(callId))
        }
        with(AppNotifier) { tapIntent.putNotificationOwner(expectedUserId) }
        val pi = PendingIntent.getActivity(
            context, NotificationSlotPolicy.missedCallNotifyId(callId), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = context.getString(if (isVideo) R.string.notification_missed_video_call else R.string.notification_missed_audio_call)
        val body = if (AppNotifier.shouldHideSensitiveDetails(context)) {
            context.getString(R.string.notification_missed_call_private)
        } else {
            context.getString(R.string.notification_missed_call_body, callerName)
        }
        val notification = NotificationCompat.Builder(context, AppNotifier.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(AppNotifier.genericNotification(context, AppNotifier.CHANNEL_CALLS, R.string.notification_missed_call_private))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (!AppNotifier.notificationOwnerMatches(context, expectedUserId)) return
        // 8.44：未接来电通知用独立 tag，避免与来电/动态互动在 null-tag id 空间哈希碰撞互相顶掉
        AppNotifier.safeNotify(context, NotificationSlotPolicy.MISSED_CALL_TAG, NotificationSlotPolicy.missedCallNotifyId(callId), notification, expectedUserId)
        // 同步到通知中心：App 锁开启时隐藏联系人姓名
        runCatching {
            val hideDetails = AppNotifier.shouldHideSensitiveDetails(context)
            com.maodouchat.MaodouchatApp.emitNotificationCenterItem(
                NotificationCenterItem(
                    id = "missed_${callId}",
                    type = NotificationCenterType.MISSED_CALL,
                    mergeKey = if (hideDetails) "missed_private" else "missed_${callerName}",
                    title = title,
                    subtitle = if (hideDetails) null else callerName,
                    preview = if (hideDetails) {
                        context.getString(R.string.notification_missed_call_private)
                    } else {
                        context.getString(if (isVideo) R.string.call_video else R.string.call_audio)
                    },
                    // Shared path with EXTRA_OPEN_MISSED_CALL tray tap.
                    deeplink = "maodouchat:missed_calls",
                    extra = mapOf("callId" to callId) + if (hideDetails) emptyMap() else mapOf("caller" to callerName)
                ),
                expectedUserId = expectedUserId,
            )
        }
    }

    fun showIncomingCall(
        context: Context,
        callId: String,
        isVideo: Boolean,
        soundEnabled: Boolean = true,
        senderId: String = "",
        expectedUserId: String,
    ) {
        if (!AppNotifier.notificationOwnerMatches(context, expectedUserId)) return
        AppNotifier.ensureChannels(context)
        if (!AppNotifier.canPostNotifications(context)) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(AppNotifier.EXTRA_OPEN_INCOMING_CALL, true)
            putExtra(AppNotifier.EXTRA_INCOMING_CALL_ID, callId)
            putExtra(AppNotifier.EXTRA_INCOMING_CALL_VIDEO, isVideo)
            if (senderId.isNotBlank()) putExtra(AppNotifier.EXTRA_INCOMING_CALL_SENDER_ID, senderId)
            data = Uri.parse(NotificationSlotPolicy.incomingCallDataUri(callId))
        }
        with(AppNotifier) { tapIntent.putNotificationOwner(expectedUserId) }
        val pi = PendingIntent.getActivity(
            context, NotificationSlotPolicy.incomingCallNotifyId(callId), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, AppNotifier.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(if (isVideo) R.string.notification_encrypted_video_call else R.string.notification_encrypted_audio_call))
            .setContentText(context.getString(R.string.notification_open_to_answer))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(AppNotifier.genericNotification(context, AppNotifier.CHANNEL_CALLS, R.string.notification_open_to_answer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setOngoing(true)
            .setTimeoutAfter(35_000L)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setSilent(!AppNotifier.effectiveRingtoneEnabled(context, soundEnabled))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }
        if (!AppNotifier.notificationOwnerMatches(context, expectedUserId)) return
        // 8.44：来电通知独立 tag（与前台服务 9001 / 动态互动隔离）
        AppNotifier.safeNotify(context, NotificationSlotPolicy.CALL_TAG, NotificationSlotPolicy.incomingCallNotifyId(callId), builder.build(), expectedUserId)
    }

    /** 对端已挂断 / 本地已接听或拒绝后清掉系统来电通知，避免幽灵响铃 */
    fun cancelIncomingCall(context: Context, callId: String) {
        if (callId.isBlank()) return
        NotificationManagerCompat.from(context).cancel(NotificationSlotPolicy.CALL_TAG, NotificationSlotPolicy.incomingCallNotifyId(callId))
        // Also clear legacy same-as-callId slot from older builds that shared the id
        // with missed calls (harmless if empty).
        NotificationManagerCompat.from(context).cancel(callId.hashCode())
    }

    /**
     * Missed-call tray uses [NotificationSlotPolicy.missedCallNotifyId] — independent of
     * [NotificationSlotPolicy.incomingCallNotifyId] so endCall / cancelIncoming never
     * erase a just-posted missed entry.
     */
    fun cancelMissedCall(context: Context, callId: String) {
        if (callId.isBlank()) return
        NotificationManagerCompat.from(context).cancel(NotificationSlotPolicy.MISSED_CALL_TAG, NotificationSlotPolicy.missedCallNotifyId(callId))
        // Legacy slot (pre-split notify ids).
        NotificationManagerCompat.from(context).cancel(callId.hashCode())
    }
}
