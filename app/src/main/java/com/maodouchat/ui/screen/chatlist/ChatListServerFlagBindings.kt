package com.maodouchat.ui.screen.chatlist

import com.maodouchat.util.Flag
import com.maodouchat.util.RuntimeFlags

/**
 * `RuntimeFlags` 常量 ↔ `parseServerFeatureFlags` 返回的 key 的绑定表（G143）。
 *
 * 原来 `ChatListScreen` 的 `LaunchedEffect(Unit)` 里 86 行
 * `RuntimeFlags.setEnabled(context, RuntimeFlags.XXX, flags["yyyEnabled"]!!)`——
 * 每一行都把「常量名」和「flags key」硬写在一起。收成一张表后，
 * 「加一个开关」只需在表里加一行，不会漏改另一处。
 *
 * 这张表**必须与 `parseServerFeatureFlags` 的 key 集合一一对应**：
 * 常量 86 个、key 86 个、pair 86 个，全部唯一（生成时逐条校验过）。
 * 两边不同步会在运行时 `!!` 抛空指针，所以 key 缺失时直接跳过而不是崩。
 */
internal val FLAG_BINDINGS: List<Pair<Flag, String>> = listOf(
        RuntimeFlags.APP_LOCK to "appLockEnabled",
        RuntimeFlags.AUTO_DOWNLOAD to "autoDownloadEnabled",
        RuntimeFlags.BLIND_WATERMARK to "blindWatermarkEnabled",
        RuntimeFlags.BLOCK_REPORT to "blockReportEnabled",
        RuntimeFlags.CALLS to "callsEnabled",
        RuntimeFlags.CAPTURE_ALERT to "captureAlertEnabled",
        RuntimeFlags.CHAT_ANIMATIONS to "chatAnimationsEnabled",
        RuntimeFlags.CHAT_ARCHIVE to "chatArchiveEnabled",
        RuntimeFlags.CHAT_DRAFTS to "chatDraftsEnabled",
        RuntimeFlags.CHAT_EXPORT to "chatExportEnabled",
        RuntimeFlags.CHAT_FOLDERS to "chatFoldersEnabled",
        RuntimeFlags.CHAT_FONT_SCALE to "chatFontScaleEnabled",
        RuntimeFlags.CHAT_LOCK to "chatLockEnabled",
        RuntimeFlags.CHAT_MUTE to "chatMuteEnabled",
        RuntimeFlags.CHAT_PIN to "chatPinEnabled",
        RuntimeFlags.CHAT_WALLPAPER to "chatWallpaperEnabled",
        RuntimeFlags.CONTACT_CARD to "contactCardEnabled",
        RuntimeFlags.DISAPPEARING_MESSAGES to "disappearingMessagesEnabled",
        RuntimeFlags.DND to "dndEnabled",
        RuntimeFlags.FILE_SHARE to "fileShareEnabled",
        RuntimeFlags.FRIEND_REQUESTS to "friendRequestsEnabled",
        RuntimeFlags.GIF_SEND to "gifSendEnabled",
        RuntimeFlags.GLOBAL_SEARCH to "globalSearchEnabled",
        RuntimeFlags.GROUP_INVITES to "groupInvitesEnabled",
        RuntimeFlags.GROUP_PLAY to "groupPlayEnabled",
        RuntimeFlags.HAPTICS to "hapticsEnabled",
        RuntimeFlags.IMAGE_SEND to "imageSendEnabled",
        RuntimeFlags.IN_APP_SOUNDS to "inAppSoundsEnabled",
        RuntimeFlags.LINK_PREVIEW to "linkPreviewEnabled",
        RuntimeFlags.LIVE_LOCATION to "liveLocationEnabled",
        RuntimeFlags.MARKDOWN to "markdownEnabled",
        RuntimeFlags.MARKED_UNREAD to "markedUnreadEnabled",
        RuntimeFlags.MEDIA_UPLOAD to "mediaUploadEnabled",
        RuntimeFlags.MENTIONS to "mentionsEnabled",
        RuntimeFlags.MESSAGE_EDIT to "messageEditEnabled",
        RuntimeFlags.MESSAGE_FORWARDING to "messageForwardingEnabled",
        RuntimeFlags.MESSAGE_PIN to "messagePinEnabled",
        RuntimeFlags.MESSAGE_REVOKE to "messageRevokeEnabled",
        RuntimeFlags.MESSAGE_STARRING to "messageStarringEnabled",
        RuntimeFlags.NAV_TRANSITIONS to "navTransitionsEnabled",
        RuntimeFlags.NEARBY to "nearbyEnabled",
        RuntimeFlags.NOTIFICATION_PREVIEW to "notificationPreviewEnabled",
        RuntimeFlags.NOTIFICATION_SOUND to "notificationSoundEnabled",
        RuntimeFlags.NUDGE to "nudgeEnabled",
        RuntimeFlags.POLLS to "pollsEnabled",
        RuntimeFlags.POSTS to "postsEnabled",
        RuntimeFlags.PRESENCE to "presenceEnabled",
        RuntimeFlags.PUSH_NOTIFICATIONS to "pushNotificationsEnabled",
        RuntimeFlags.QR_CODE to "qrCodeEnabled",
        RuntimeFlags.REACTIONS to "reactionsEnabled",
        RuntimeFlags.READ_RECEIPTS to "readReceiptsEnabled",
        RuntimeFlags.RECENTS_EXCLUSION to "recentsExclusionEnabled",
        RuntimeFlags.RINGTONE to "ringtoneEnabled",
        RuntimeFlags.SAFETY_CODE to "safetyCodeEnabled",
        RuntimeFlags.SCHEDULED_MESSAGES to "scheduledMessagesEnabled",
        RuntimeFlags.SCREEN_SECURE to "screenSecureRuntimeEnabled",
        RuntimeFlags.SCREENSHOT_DETECT to "screenshotDetectEnabled",
        RuntimeFlags.SEALED_SENDER to "sealedSenderEnabled",
        RuntimeFlags.SECRET_AUTO_DISAPPEAR to "secretAutoDisappearEnabled",
        RuntimeFlags.SECRET_CHAT_EXPORT_BLOCK to "secretChatExportBlockEnabled",
        RuntimeFlags.SECRET_CHAT to "secretChatEnabled",
        RuntimeFlags.SECRET_COPY_BLOCK to "secretCopyBlockEnabled",
        RuntimeFlags.SECRET_EXTERNAL_LINK_BLOCK to "secretExternalLinkBlockEnabled",
        RuntimeFlags.SECRET_FORWARD_BLOCK to "secretForwardBlockEnabled",
        RuntimeFlags.SECRET_LINK_PREVIEW_BLOCK to "secretLinkPreviewBlockEnabled",
        RuntimeFlags.SECRET_LIST_PREVIEW_BLOCK to "secretListPreviewBlockEnabled",
        RuntimeFlags.SECRET_MEDIA_EXPORT_BLOCK to "secretMediaExportBlockEnabled",
        RuntimeFlags.SECRET_NOTIF_PREVIEW_BLOCK to "secretNotifPreviewBlockEnabled",
        RuntimeFlags.SECRET_REACTION_BLOCK to "secretReactionBlockEnabled",
        RuntimeFlags.SECRET_STAR_BLOCK to "secretStarBlockEnabled",
        RuntimeFlags.SECRET_TYPING_BLOCK to "secretTypingBlockEnabled",
        RuntimeFlags.SECRET_READ_RECEIPT_BLOCK to "secretReadReceiptBlockEnabled",
        RuntimeFlags.SECRET_PRESENCE_BLOCK to "secretPresenceBlockEnabled",
        RuntimeFlags.SECRET_LAST_SEEN_BLOCK to "secretLastSeenBlockEnabled",
        RuntimeFlags.SILENT_SEND to "silentSendEnabled",
        RuntimeFlags.SPOILER_MEDIA to "spoilerMediaEnabled",
        RuntimeFlags.STATIC_LOCATION to "staticLocationEnabled",
        RuntimeFlags.STICKERS to "stickersEnabled",
        RuntimeFlags.TASK_REMINDERS to "taskRemindersEnabled",
        RuntimeFlags.TYPING_INDICATORS to "typingIndicatorsEnabled",
        RuntimeFlags.UNREAD_PRIORITY to "unreadPriorityEnabled",
        RuntimeFlags.VIDEO_CALL to "videoCallEnabled",
        RuntimeFlags.VIDEO_SEND to "videoSendEnabled",
        RuntimeFlags.VIEW_ONCE to "viewOnceEnabled",
        RuntimeFlags.VOICE_CALL to "voiceCallEnabled",
        RuntimeFlags.VOICE_MESSAGES to "voiceMessagesEnabled",
)

/**
 * 把服务端下发的功能开关一次性写进 `RuntimeFlags`（G143）。
 *
 * 纯搬移，不改判断——绑定的每对关系与原先 86 行手写调用完全一致。
 */
internal fun applyServerFeatureFlags(
    context: android.content.Context,
    flags: Map<String, Boolean>,
) {
    FLAG_BINDINGS.forEach { (feature, key) ->
        flags[key]?.let { RuntimeFlags.setEnabled(context, feature, it) }
    }
}
