package com.maodouchat.ui.screen.chatlist

import com.maodouchat.util.RuntimeFlags
import com.maodouchat.network.ApiService
import org.json.JSONObject
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.maodouchat.data.repository.PublicServerInfoRepository

/**
 * 服务端下发的功能开关批量解析（G142 从 `ChatListScreen.kt` 的 `LaunchedEffect(Unit)` 拆出，
 * 原 86 行 `val xxxEnabledOn = if (o.has(...)) o.optBoolean(...) else ...`）。
 *
 * 原来每条都写 `if (o.has(key)) o.optBoolean(key, default) else default`——
 * 这两支其实等价（`optBoolean` 在键缺失时就返回传入的默认值），
 * 所以收敛成一次 `optBoolean(key, default)`，语义不变。
 *
 * **默认值不是统一的**：`chatExportEnabled` / `nearbyEnabled` /
 * `secretExternalLinkBlockEnabled` 三项默认 **false**（新功能默认关），其余默认 **true**。
 * 这个差异必须逐条保留——改错一项就会「服务端没下发时行为反转」。
 *
 * 纯搬移，不改判断。
 */
internal fun parseServerFeatureFlags(o: JSONObject): Map<String, Boolean> = mapOf(
        "appLockEnabled" to o.optBoolean("appLockEnabled", true),
        "autoDownloadEnabled" to o.optBoolean("autoDownloadEnabled", true),
        "blindWatermarkEnabled" to o.optBoolean("blindWatermarkEnabled", true),
        "blockReportEnabled" to o.optBoolean("blockReportEnabled", true),
        "callsEnabled" to o.optBoolean("callsEnabled", true),
        "captureAlertEnabled" to o.optBoolean("captureAlertEnabled", true),
        "chatAnimationsEnabled" to o.optBoolean("chatAnimationsEnabled", true),
        "chatArchiveEnabled" to o.optBoolean("chatArchiveEnabled", true),
        "chatDraftsEnabled" to o.optBoolean("chatDraftsEnabled", true),
        "chatExportEnabled" to o.optBoolean("chatExportEnabled", false),
        "chatFoldersEnabled" to o.optBoolean("chatFoldersEnabled", true),
        "chatFontScaleEnabled" to o.optBoolean("chatFontScaleEnabled", true),
        "chatLockEnabled" to o.optBoolean("chatLockEnabled", true),
        "chatMuteEnabled" to o.optBoolean("chatMuteEnabled", true),
        "chatPinEnabled" to o.optBoolean("chatPinEnabled", true),
        "chatWallpaperEnabled" to o.optBoolean("chatWallpaperEnabled", true),
        "contactCardEnabled" to o.optBoolean("contactCardEnabled", true),
        "disappearingMessagesEnabled" to o.optBoolean("disappearingMessagesEnabled", true),
        "dndEnabled" to o.optBoolean("dndEnabled", true),
        "fileShareEnabled" to o.optBoolean("fileShareEnabled", true),
        "friendRequestsEnabled" to o.optBoolean("friendRequestsEnabled", true),
        "gifSendEnabled" to o.optBoolean("gifSendEnabled", true),
        "globalSearchEnabled" to o.optBoolean("globalSearchEnabled", true),
        "groupInvitesEnabled" to o.optBoolean("groupInvitesEnabled", true),
        "groupPlayEnabled" to o.optBoolean("groupPlayEnabled", true),
        "hapticsEnabled" to o.optBoolean("hapticsEnabled", true),
        "imageSendEnabled" to o.optBoolean("imageSendEnabled", true),
        "inAppSoundsEnabled" to o.optBoolean("inAppSoundsEnabled", true),
        "linkPreviewEnabled" to o.optBoolean("linkPreviewEnabled", true),
        "liveLocationEnabled" to o.optBoolean("liveLocationEnabled", true),
        "markdownEnabled" to o.optBoolean("markdownEnabled", true),
        "markedUnreadEnabled" to o.optBoolean("markedUnreadEnabled", true),
        "mediaUploadEnabled" to o.optBoolean("mediaUploadEnabled", true),
        "mentionsEnabled" to o.optBoolean("mentionsEnabled", true),
        "messageEditEnabled" to o.optBoolean("messageEditEnabled", true),
        "messageForwardingEnabled" to o.optBoolean("messageForwardingEnabled", true),
        "messagePinEnabled" to o.optBoolean("messagePinEnabled", true),
        "messageRevokeEnabled" to o.optBoolean("messageRevokeEnabled", true),
        "messageStarringEnabled" to o.optBoolean("messageStarringEnabled", true),
        "navTransitionsEnabled" to o.optBoolean("navTransitionsEnabled", true),
        "nearbyEnabled" to o.optBoolean("nearbyEnabled", false),
        "notificationPreviewEnabled" to o.optBoolean("notificationPreviewEnabled", true),
        "notificationSoundEnabled" to o.optBoolean("notificationSoundEnabled", true),
        "nudgeEnabled" to o.optBoolean("nudgeEnabled", true),
        "pollsEnabled" to o.optBoolean("pollsEnabled", true),
        "postsEnabled" to o.optBoolean("postsEnabled", true),
        "presenceEnabled" to o.optBoolean("presenceEnabled", true),
        "pushNotificationsEnabled" to o.optBoolean("pushNotificationsEnabled", true),
        "qrCodeEnabled" to o.optBoolean("qrCodeEnabled", true),
        "reactionsEnabled" to o.optBoolean("reactionsEnabled", true),
        "readReceiptsEnabled" to o.optBoolean("readReceiptsEnabled", true),
        "recentsExclusionEnabled" to o.optBoolean("recentsExclusionEnabled", true),
        "ringtoneEnabled" to o.optBoolean("ringtoneEnabled", true),
        "safetyCodeEnabled" to o.optBoolean("safetyCodeEnabled", true),
        "scheduledMessagesEnabled" to o.optBoolean("scheduledMessagesEnabled", true),
        "screenSecureRuntimeEnabled" to o.optBoolean("screenSecureRuntimeEnabled", true),
        "screenshotDetectEnabled" to o.optBoolean("screenshotDetectEnabled", true),
        "sealedSenderEnabled" to o.optBoolean("sealedSenderEnabled", true),
        "secretAutoDisappearEnabled" to o.optBoolean("secretAutoDisappearEnabled", true),
        "secretChatExportBlockEnabled" to o.optBoolean("secretChatExportBlockEnabled", true),
        "secretChatEnabled" to o.optBoolean("secretChatEnabled", true),
        "secretCopyBlockEnabled" to o.optBoolean("secretCopyBlockEnabled", true),
        "secretExternalLinkBlockEnabled" to o.optBoolean("secretExternalLinkBlockEnabled", false),
        "secretForwardBlockEnabled" to o.optBoolean("secretForwardBlockEnabled", true),
        "secretLinkPreviewBlockEnabled" to o.optBoolean("secretLinkPreviewBlockEnabled", true),
        "secretListPreviewBlockEnabled" to o.optBoolean("secretListPreviewBlockEnabled", true),
        "secretMediaExportBlockEnabled" to o.optBoolean("secretMediaExportBlockEnabled", true),
        "secretNotifPreviewBlockEnabled" to o.optBoolean("secretNotifPreviewBlockEnabled", true),
        "secretReactionBlockEnabled" to o.optBoolean("secretReactionBlockEnabled", true),
        "secretStarBlockEnabled" to o.optBoolean("secretStarBlockEnabled", true),
        "secretTypingBlockEnabled" to o.optBoolean("secretTypingBlockEnabled", true),
        "secretReadReceiptBlockEnabled" to o.optBoolean("secretReadReceiptBlockEnabled", true),
        "secretPresenceBlockEnabled" to o.optBoolean("secretPresenceBlockEnabled", true),
        "secretLastSeenBlockEnabled" to o.optBoolean("secretLastSeenBlockEnabled", true),
        "silentSendEnabled" to o.optBoolean("silentSendEnabled", true),
        "spoilerMediaEnabled" to o.optBoolean("spoilerMediaEnabled", true),
        "staticLocationEnabled" to o.optBoolean("staticLocationEnabled", true),
        "stickersEnabled" to o.optBoolean("stickersEnabled", true),
        "taskRemindersEnabled" to o.optBoolean("taskRemindersEnabled", true),
        "typingIndicatorsEnabled" to o.optBoolean("typingIndicatorsEnabled", true),
        "unreadPriorityEnabled" to o.optBoolean("unreadPriorityEnabled", true),
        "videoCallEnabled" to o.optBoolean("videoCallEnabled", true),
        "videoSendEnabled" to o.optBoolean("videoSendEnabled", true),
        "viewOnceEnabled" to o.optBoolean("viewOnceEnabled", true),
        "voiceCallEnabled" to o.optBoolean("voiceCallEnabled", true),
        "voiceMessagesEnabled" to o.optBoolean("voiceMessagesEnabled", true),
)


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
internal val FLAG_BINDINGS: List<Pair<com.maodouchat.util.RuntimeFlags.Flag, String>> = listOf(
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
        flags[key]?.let { com.maodouchat.util.RuntimeFlags.setEnabled(context, feature, it) }
    }
}


/**
 * 拉取 `/api/public/status` 并把公屏状态落到本地（G144 从 `ChatListScreen.kt` 的
 * `LaunchedEffect(Unit)` 拆出，原 53 行）。
 *
 * 一次调用做四件事：
 * 1. `safeOpt` 取横幅 / E2EE 横幅 / 公告 / 维护文案 / 最低版本——`safeOpt` 排除
 *    `optString` 在键缺失时返回的字面 `"null"`，避免横幅显示 "null" 或写入垃圾 key；
 * 2. `parseServerFeatureFlags` + `applyServerFeatureFlags` 写入 86 个功能开关，
 *    以及 `pushHmacKey`、AI 总闸（`aiEnabled`）、`secretSurfaceFlags` 八项密聊默认值；
 * 3. 拼出公屏横幅文本（维护文案优先，否则各段用 " · " 连接）；
 * 4. 返回该横幅（null 表示不显示）。
 *
 * **键名兼容**：服务端下发 `maintenance`，旧版本曾用 `maintenanceMode`——
 * 两条都试，保持老服务端可用。
 *
 * **纯搬移，不改判断**：每条默认值、每个键名、优先级顺序都与原先一致。
 */
internal suspend fun fetchPublicStatusBanner(context: android.content.Context): String? =
    withContext(Dispatchers.IO) {
        // G328c：传输层调用移到 data 层（PublicServerInfoRepository），ui 只依赖 repository。
        val raw = PublicServerInfoRepository().publicStatus().orEmpty()
        if (raw.isBlank()) return@withContext null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
        // optString 缺失键返回字面 "null"（非 blank）——统一用 safeOpt 排除，避免横幅显示 "null"/写入垃圾 key
        fun safeOpt(key: String): String = if (o.has(key)) o.optString(key).takeIf { it != "null" }.orEmpty() else ""
        val banner = safeOpt("banner").ifBlank { safeOpt("globalBanner") }
        val e2eeBanner = safeOpt("forceE2eeBanner").ifBlank { safeOpt("e2eeBanner") }
        val announcement = safeOpt("publicAnnouncement").ifBlank { safeOpt("announcement") }
        val maintMsg = safeOpt("maintenanceMessage")
        // 兼容键名：服务端下发 "maintenance"；旧版本曾用 "maintenanceMode"
        val publicMaintenance = if (o.has("maintenance")) o.optBoolean("maintenance", false) else o.optBoolean("maintenanceMode", false)
        val minApp = safeOpt("minAppVersion")
        val pqxdh = o.optBoolean("pqxdhPreview", false)

        val flags = parseServerFeatureFlags(o)
        val pushHmacKey = safeOpt("pushHmacKey").ifBlank { null }

        // G143：86 行开关写入收敛成一次调用（绑定表见 ChatListServerFlags.FLAG_BINDINGS）
        applyServerFeatureFlags(context, flags)
        pushHmacKey?.let { com.maodouchat.util.PushVerifyPrefs.setKey(context, it) }

        // 服务端 AI 总开关 → 本地 AI_MASTER（false 时折叠全部 AI 入口）
        if (o.has("aiEnabled")) RuntimeFlags.setEnabled(context, RuntimeFlags.AI_MASTER, o.optBoolean("aiEnabled", true))
        // B2 密聊防泄漏扩展（surface #71–#78）：服务端开关 → SecretXxxPrefs。
        // 仅当用户从未在设置页显式设置过时接受服务端默认值，本地开关永远优先
        //（设置页声明"仅本机生效"；无条件覆盖会让用户"关了又开"）。
        val ssf = o.optJSONObject("secretSurfaceFlags")
        if (ssf != null) {
            com.maodouchat.util.SecretScreenshotBurnPrefs.applyServerDefault(context, ssf.optBoolean("secretScreenshotBurnEnabled", true))
            com.maodouchat.util.SecretAutoDestroyPrefs.applyServerDefault(context, ssf.optBoolean("secretAutoDestroyEnabled", true))
            com.maodouchat.util.SecretForwardWhitelistPrefs.applyServerDefault(context, ssf.optBoolean("secretForwardWhitelistEnabled", true))
            com.maodouchat.util.SecretSimChangePrefs.applyServerDefault(context, ssf.optBoolean("secretSimChangeProtectionEnabled", true))
            com.maodouchat.util.Secret2faGatePrefs.applyServerDefault(context, ssf.optBoolean("secret2faGateEnabled", true))
            com.maodouchat.util.SecretNewDeviceRiskPrefs.applyServerDefault(context, ssf.optBoolean("secretNewDeviceRiskEnabled", true))
            com.maodouchat.util.SecretDeviceVerifyPrefs.applyServerDefault(context, ssf.optBoolean("secretDeviceVerifyEnabled", true))
            com.maodouchat.util.SecretSessionNoticePrefs.applyServerDefault(context, ssf.optBoolean("secretSessionNoticeEnabled", true))
        }
        val upgradeHint = if (minApp.isNotBlank() && minApp != "0") "Min version: $minApp" else null
        val pqxdhHint = if (pqxdh) "PQXDH preview on" else null
        val parts = listOfNotNull(
            banner.takeIf { it.isNotBlank() },
            e2eeBanner.takeIf { it.isNotBlank() },
            announcement.takeIf { it.isNotBlank() },
            upgradeHint,
            pqxdhHint
        )
        when {
            publicMaintenance && maintMsg.isNotBlank() -> maintMsg
            parts.isNotEmpty() -> parts.joinToString(" · ")
            else -> null
        }    }
