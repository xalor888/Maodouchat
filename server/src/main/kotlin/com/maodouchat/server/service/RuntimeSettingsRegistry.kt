package com.maodouchat.server.service

import com.maodouchat.server.config.ServerConfig

enum class RuntimeSettingType { BOOLEAN, STRING, INT, LONG, STRING_SET }

data class RuntimeSettingSpec(
    val key: String,
    val type: RuntimeSettingType,
    val default: String,
    val sensitive: Boolean = false,
    val restartRequired: Boolean = false,
    val min: Long? = null,
    val max: Long? = null,
)

/**
 * B13：typed runtime settings registry——单一事实源，描述每个运行时开关的类型、默认值、
 * 数值范围、敏感性与重启要求。供管理后台渲染类型化编辑器，以及 [RuntimeConfigService]
 * 在 set/setMany 时做类型与范围校验。布尔/字符串占绝大多数；数值开关只此四处，范围即
 * 各 getter 里的 coerceIn 上/下界。
 */
object RuntimeSettingsRegistry {
    private fun bool(key: String, default: Boolean) =
        RuntimeSettingSpec(key, RuntimeSettingType.BOOLEAN, default.toString())
    private fun str(key: String, default: String) =
        RuntimeSettingSpec(key, RuntimeSettingType.STRING, default)
    private fun int(key: String, default: String, min: Long? = null, max: Long? = null) =
        RuntimeSettingSpec(key, RuntimeSettingType.INT, default, min = min, max = max)
    private fun long(key: String, default: String, min: Long? = null, max: Long? = null) =
        RuntimeSettingSpec(key, RuntimeSettingType.LONG, default, min = min, max = max)
    private fun strSet(key: String, default: String) =
        RuntimeSettingSpec(key, RuntimeSettingType.STRING_SET, default)

    val specs: List<RuntimeSettingSpec> = listOf(
        bool(RuntimeConfigService.KEY_ALLOW_REGISTRATION, ServerConfig.allowRegistration),
        bool(RuntimeConfigService.KEY_MAINTENANCE_MODE, false),
        str(RuntimeConfigService.KEY_MAINTENANCE_MESSAGE, "System under maintenance. Please try again later."),
        str(RuntimeConfigService.KEY_GLOBAL_BANNER, ""),
        str(RuntimeConfigService.KEY_INVITE_ONLY_HINT, "Registration is temporarily closed."),
        int(RuntimeConfigService.KEY_MAX_GROUP_SIZE, "200", min = 2L, max = 5000L),
        bool(RuntimeConfigService.KEY_SEALED_SENDER_ENABLED, true),
        bool(RuntimeConfigService.KEY_ALLOW_BOTS, true),
        str(RuntimeConfigService.KEY_FORCE_E2EE_BANNER, ""),
        int(RuntimeConfigService.KEY_MAX_MESSAGE_PER_MIN, "180", min = 10L, max = 600L),
        strSet(RuntimeConfigService.KEY_IP_BLOCKLIST, ""),
        bool(RuntimeConfigService.KEY_AI_ENABLED, true),
        bool(RuntimeConfigService.KEY_AI_CONTENT_MODERATION_ENABLED, false),
        str(RuntimeConfigService.KEY_PUBLIC_ANNOUNCEMENT, ""),
        bool(RuntimeConfigService.KEY_PQXDH_PREVIEW, false),
        str(RuntimeConfigService.KEY_MIN_APP_VERSION, "0"),
        int(RuntimeConfigService.KEY_UPDATE_VERSION_CODE, "0"),
        str(RuntimeConfigService.KEY_UPDATE_VERSION_NAME, ""),
        str(RuntimeConfigService.KEY_UPDATE_APK_URL, ""),
        str(RuntimeConfigService.KEY_UPDATE_APK_SHA256, ""),
        str(RuntimeConfigService.KEY_UPDATE_SERVER_URL, ""),
        str(RuntimeConfigService.KEY_UPDATE_NOTES, ""),
        int(RuntimeConfigService.KEY_MAX_BOTS_PER_USER, "20", min = 1L, max = 200L),
        bool(RuntimeConfigService.KEY_CAPTURE_ALERT_ENABLED, true),
        bool(RuntimeConfigService.KEY_MEDIA_UPLOAD_ENABLED, true),
        bool(RuntimeConfigService.KEY_GROUP_PLAY_ENABLED, true),
        bool(RuntimeConfigService.KEY_LINK_PREVIEW_ENABLED, true),
        bool(RuntimeConfigService.KEY_VOICE_MESSAGES_ENABLED, true),
        bool(RuntimeConfigService.KEY_REACTIONS_ENABLED, true),
        bool(RuntimeConfigService.KEY_STICKERS_ENABLED, true),
        bool(RuntimeConfigService.KEY_SILENT_SEND_ENABLED, true),
        bool(RuntimeConfigService.KEY_CALLS_ENABLED, true),
        bool(RuntimeConfigService.KEY_SCHEDULED_MESSAGES_ENABLED, true),
        bool(RuntimeConfigService.KEY_VIEW_ONCE_ENABLED, true),
        bool(RuntimeConfigService.KEY_LIVE_LOCATION_ENABLED, true),
        bool(RuntimeConfigService.KEY_MARKDOWN_ENABLED, true),
        bool(RuntimeConfigService.KEY_TYPING_INDICATORS_ENABLED, true),
        bool(RuntimeConfigService.KEY_READ_RECEIPTS_ENABLED, true),
        bool(RuntimeConfigService.KEY_PRESENCE_ENABLED, true),
        bool(RuntimeConfigService.KEY_MESSAGE_STARRING_ENABLED, true),
        bool(RuntimeConfigService.KEY_CHAT_EXPORT_ENABLED, false),
        bool(RuntimeConfigService.KEY_MESSAGE_FORWARDING_ENABLED, true),
        bool(RuntimeConfigService.KEY_GLOBAL_SEARCH_ENABLED, true),
        bool(RuntimeConfigService.KEY_FRIEND_REQUESTS_ENABLED, true),
        bool(RuntimeConfigService.KEY_CHAT_FOLDERS_ENABLED, true),
        bool(RuntimeConfigService.KEY_POSTS_ENABLED, true),
        bool(RuntimeConfigService.KEY_BLOCK_REPORT_ENABLED, true),
        bool(RuntimeConfigService.KEY_CHANNELS_ENABLED, true),
        bool(RuntimeConfigService.KEY_CHAT_ARCHIVE_ENABLED, true),
        bool(RuntimeConfigService.KEY_NEARBY_ENABLED, false),
        bool(RuntimeConfigService.KEY_CHAT_PIN_ENABLED, true),
        bool(RuntimeConfigService.KEY_MARKED_UNREAD_ENABLED, true),
        bool(RuntimeConfigService.KEY_CHAT_MUTE_ENABLED, true),
        bool(RuntimeConfigService.KEY_DISAPPEARING_MESSAGES_ENABLED, true),
        bool(RuntimeConfigService.KEY_CHAT_LOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_MESSAGE_EDIT_ENABLED, true),
        bool(RuntimeConfigService.KEY_MESSAGE_PIN_ENABLED, true),
        bool(RuntimeConfigService.KEY_MESSAGE_REVOKE_ENABLED, true),
        bool(RuntimeConfigService.KEY_POLLS_ENABLED, true),
        bool(RuntimeConfigService.KEY_APP_LOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_CHAT_DRAFTS_ENABLED, true),
        bool(RuntimeConfigService.KEY_GROUP_INVITES_ENABLED, true),
        bool(RuntimeConfigService.KEY_MENTIONS_ENABLED, true),
        bool(RuntimeConfigService.KEY_NUDGE_ENABLED, true),
        bool(RuntimeConfigService.KEY_SAFETY_CODE_ENABLED, true),
        bool(RuntimeConfigService.KEY_QR_CODE_ENABLED, true),
        bool(RuntimeConfigService.KEY_CONTACT_CARD_ENABLED, true),
        bool(RuntimeConfigService.KEY_SPOILER_MEDIA_ENABLED, true),
        bool(RuntimeConfigService.KEY_AUTO_DOWNLOAD_ENABLED, true),
        bool(RuntimeConfigService.KEY_STATIC_LOCATION_ENABLED, true),
        bool(RuntimeConfigService.KEY_FILE_SHARE_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_CHAT_ENABLED, true),
        bool(RuntimeConfigService.KEY_SCREEN_SECURE_RUNTIME_ENABLED, true),
        bool(RuntimeConfigService.KEY_IMAGE_SEND_ENABLED, true),
        bool(RuntimeConfigService.KEY_VIDEO_SEND_ENABLED, true),
        long(RuntimeConfigService.KEY_AI_DAILY_TOKEN_BUDGET_PER_USER, "200000", min = 0L, max = 1000000000L),
        bool(RuntimeConfigService.KEY_AI_RETRY_ENABLED, true),
        bool(RuntimeConfigService.KEY_GIF_SEND_ENABLED, true),
        bool(RuntimeConfigService.KEY_BLIND_WATERMARK_ENABLED, true),
        bool(RuntimeConfigService.KEY_VOICE_CALL_ENABLED, true),
        bool(RuntimeConfigService.KEY_VIDEO_CALL_ENABLED, true),
        bool(RuntimeConfigService.KEY_CHAT_WALLPAPER_ENABLED, true),
        bool(RuntimeConfigService.KEY_CHAT_FONT_SCALE_ENABLED, true),
        bool(RuntimeConfigService.KEY_UNREAD_PRIORITY_ENABLED, true),
        bool(RuntimeConfigService.KEY_RINGTONE_ENABLED, true),
        bool(RuntimeConfigService.KEY_NOTIFICATION_SOUND_ENABLED, true),
        bool(RuntimeConfigService.KEY_NOTIFICATION_PREVIEW_ENABLED, true),
        bool(RuntimeConfigService.KEY_PUSH_NOTIFICATIONS_ENABLED, true),
        bool(RuntimeConfigService.KEY_TASK_REMINDERS_ENABLED, true),
        bool(RuntimeConfigService.KEY_DND_ENABLED, true),
        bool(RuntimeConfigService.KEY_IN_APP_SOUNDS_ENABLED, true),
        bool(RuntimeConfigService.KEY_HAPTICS_ENABLED, true),
        bool(RuntimeConfigService.KEY_CHAT_ANIMATIONS_ENABLED, true),
        bool(RuntimeConfigService.KEY_NAV_TRANSITIONS_ENABLED, true),
        bool(RuntimeConfigService.KEY_SCREENSHOT_DETECT_ENABLED, true),
        bool(RuntimeConfigService.KEY_RECENTS_EXCLUSION_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_COPY_BLOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_MEDIA_EXPORT_BLOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_FORWARD_BLOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_CHAT_EXPORT_BLOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_AUTO_DISAPPEAR_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_LINK_PREVIEW_BLOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_EXTERNAL_LINK_BLOCK_ENABLED, false),
        bool(RuntimeConfigService.KEY_SECRET_NOTIF_PREVIEW_BLOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_LIST_PREVIEW_BLOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_REACTION_BLOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_STAR_BLOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_TYPING_BLOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_READ_RECEIPT_BLOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_PRESENCE_BLOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_LAST_SEEN_BLOCK_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_AUTO_DESTROY_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_SCREENSHOT_BURN_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_FORWARD_WHITELIST_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_SIM_CHANGE_PROTECTION_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_2FA_GATE_ENABLED, false),
        bool(RuntimeConfigService.KEY_SECRET_NEW_DEVICE_RISK_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_DEVICE_VERIFY_ENABLED, true),
        bool(RuntimeConfigService.KEY_SECRET_SESSION_NOTICE_ENABLED, true),
    )

    val byKey: Map<String, RuntimeSettingSpec> = specs.associateBy { it.key }

    fun spec(key: String): RuntimeSettingSpec? = byKey[key]
}
