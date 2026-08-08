package com.maodouchat.server.service

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.SystemSettings
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime toggles stored in [SystemSettings], with env defaults from [ServerConfig].
 * Cached briefly so hot paths (register) stay cheap.
 */
object RuntimeConfigService {
    private const val CACHE_TTL_MS = 5_000L
    private val cache = ConcurrentHashMap<String, String>()
    private val loadedAt = AtomicLong(0L)

    const val KEY_ALLOW_REGISTRATION = "allow_registration"
    const val KEY_MAINTENANCE_MODE = "maintenance_mode"
    const val KEY_MAINTENANCE_MESSAGE = "maintenance_message"
    const val KEY_GLOBAL_BANNER = "global_banner"
    const val KEY_INVITE_ONLY_HINT = "invite_only_hint"
    const val KEY_MAX_GROUP_SIZE = "max_group_size"
    const val KEY_SEALED_SENDER_ENABLED = "sealed_sender_enabled"
    const val KEY_ALLOW_BOTS = "allow_bots"
    const val KEY_FORCE_E2EE_BANNER = "force_e2ee_banner"
    const val KEY_MAX_MESSAGE_PER_MIN = "max_message_per_min"
    const val KEY_IP_BLOCKLIST = "ip_blocklist"
    const val KEY_AI_ENABLED = "ai_enabled"
    const val KEY_PUBLIC_ANNOUNCEMENT = "public_announcement"
    const val KEY_PQXDH_PREVIEW = "pqxdh_preview"
    const val KEY_MIN_APP_VERSION = "min_app_version"
    const val KEY_SECRET_CHAT_REQUIRED = "secret_chat_required"
    const val KEY_MAX_BOTS_PER_USER = "max_bots_per_user"
    const val KEY_CAPTURE_ALERT_ENABLED = "capture_alert_enabled"
    const val KEY_MEDIA_UPLOAD_ENABLED = "media_upload_enabled"
    const val KEY_GROUP_PLAY_ENABLED = "group_play_enabled"
    const val KEY_LINK_PREVIEW_ENABLED = "link_preview_enabled"
    const val KEY_VOICE_MESSAGES_ENABLED = "voice_messages_enabled"
    const val KEY_REACTIONS_ENABLED = "reactions_enabled"
    const val KEY_STICKERS_ENABLED = "stickers_enabled"
    const val KEY_SILENT_SEND_ENABLED = "silent_send_enabled"
    const val KEY_CALLS_ENABLED = "calls_enabled"
    const val KEY_SCHEDULED_MESSAGES_ENABLED = "scheduled_messages_enabled"
    const val KEY_VIEW_ONCE_ENABLED = "view_once_enabled"
    const val KEY_LIVE_LOCATION_ENABLED = "live_location_enabled"
    const val KEY_MARKDOWN_ENABLED = "markdown_enabled"
    const val KEY_TYPING_INDICATORS_ENABLED = "typing_indicators_enabled"
    const val KEY_READ_RECEIPTS_ENABLED = "read_receipts_enabled"
    const val KEY_PRESENCE_ENABLED = "presence_enabled"
    const val KEY_MESSAGE_STARRING_ENABLED = "message_starring_enabled"
    const val KEY_CHAT_EXPORT_ENABLED = "chat_export_enabled"
    const val KEY_MESSAGE_FORWARDING_ENABLED = "message_forwarding_enabled"
    const val KEY_GLOBAL_SEARCH_ENABLED = "global_search_enabled"
    const val KEY_FRIEND_REQUESTS_ENABLED = "friend_requests_enabled"
    const val KEY_CHAT_FOLDERS_ENABLED = "chat_folders_enabled"
    const val KEY_POSTS_ENABLED = "posts_enabled"
    const val KEY_BLOCK_REPORT_ENABLED = "block_report_enabled"
    const val KEY_CHANNELS_ENABLED = "channels_enabled"
    const val KEY_CHAT_ARCHIVE_ENABLED = "chat_archive_enabled"
    const val KEY_NEARBY_ENABLED = "nearby_enabled"
    const val KEY_CHAT_PIN_ENABLED = "chat_pin_enabled"
    const val KEY_MARKED_UNREAD_ENABLED = "marked_unread_enabled"
    const val KEY_CHAT_MUTE_ENABLED = "chat_mute_enabled"
    const val KEY_DISAPPEARING_MESSAGES_ENABLED = "disappearing_messages_enabled"
    const val KEY_CHAT_LOCK_ENABLED = "chat_lock_enabled"
    const val KEY_MESSAGE_EDIT_ENABLED = "message_edit_enabled"
    const val KEY_MESSAGE_PIN_ENABLED = "message_pin_enabled"
    const val KEY_MESSAGE_REVOKE_ENABLED = "message_revoke_enabled"
    const val KEY_POLLS_ENABLED = "polls_enabled"
    const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
    const val KEY_CHAT_DRAFTS_ENABLED = "chat_drafts_enabled"
    const val KEY_AI_TRANSLATE_ENABLED = "ai_translate_enabled"
    const val KEY_GROUP_INVITES_ENABLED = "group_invites_enabled"
    const val KEY_MENTIONS_ENABLED = "mentions_enabled"
    const val KEY_NUDGE_ENABLED = "nudge_enabled"
    const val KEY_SAFETY_CODE_ENABLED = "safety_code_enabled"
    const val KEY_QR_CODE_ENABLED = "qr_code_enabled"
    const val KEY_CONTACT_CARD_ENABLED = "contact_card_enabled"
    const val KEY_SPOILER_MEDIA_ENABLED = "spoiler_media_enabled"
    const val KEY_AUTO_DOWNLOAD_ENABLED = "auto_download_enabled"
    const val KEY_STATIC_LOCATION_ENABLED = "static_location_enabled"
    const val KEY_FILE_SHARE_ENABLED = "file_share_enabled"
    const val KEY_SECRET_CHAT_ENABLED = "secret_chat_enabled"
    const val KEY_SCREEN_SECURE_RUNTIME_ENABLED = "screen_secure_runtime_enabled"
    const val KEY_IMAGE_SEND_ENABLED = "image_send_enabled"
    const val KEY_VIDEO_SEND_ENABLED = "video_send_enabled"
    const val KEY_AI_SUMMARY_ENABLED = "ai_summary_enabled"
    const val KEY_AI_REWRITE_ENABLED = "ai_rewrite_enabled"
    const val KEY_AI_SUGGEST_REPLIES_ENABLED = "ai_suggest_replies_enabled"
    const val KEY_AI_TRANSCRIBE_ENABLED = "ai_transcribe_enabled"
    const val KEY_AI_ANALYZE_IMAGE_ENABLED = "ai_analyze_image_enabled"
    const val KEY_AI_GROUP_ASSISTANT_ENABLED = "ai_group_assistant_enabled"
    const val KEY_AI_ANALYZE_FILE_ENABLED = "ai_analyze_file_enabled"
    const val KEY_AI_SEMANTIC_SEARCH_ENABLED = "ai_semantic_search_enabled"
    const val KEY_AI_DAILY_TOKEN_BUDGET_PER_USER = "ai_daily_token_budget_per_user"
    const val KEY_AI_CACHE_ENABLED = "ai_cache_enabled"
    const val KEY_AI_RETRY_ENABLED = "ai_retry_enabled"
    const val KEY_AI_MULTI_MODEL_ENABLED = "ai_multi_model_enabled"
    const val KEY_GIF_SEND_ENABLED = "gif_send_enabled"
    const val KEY_BLIND_WATERMARK_ENABLED = "blind_watermark_enabled"
    const val KEY_VOICE_CALL_ENABLED = "voice_call_enabled"
    const val KEY_VIDEO_CALL_ENABLED = "video_call_enabled"
    const val KEY_CHAT_WALLPAPER_ENABLED = "chat_wallpaper_enabled"
    const val KEY_CHAT_FONT_SCALE_ENABLED = "chat_font_scale_enabled"
    const val KEY_UNREAD_PRIORITY_ENABLED = "unread_priority_enabled"
    const val KEY_RINGTONE_ENABLED = "ringtone_enabled"
    const val KEY_NOTIFICATION_SOUND_ENABLED = "notification_sound_enabled"
    const val KEY_NOTIFICATION_PREVIEW_ENABLED = "notification_preview_enabled"
    const val KEY_PUSH_NOTIFICATIONS_ENABLED = "push_notifications_enabled"
    const val KEY_TASK_REMINDERS_ENABLED = "task_reminders_enabled"
    const val KEY_DND_ENABLED = "dnd_enabled"
    const val KEY_OFFLINE_AI_ENABLED = "offline_ai_enabled"
    const val KEY_IN_APP_SOUNDS_ENABLED = "in_app_sounds_enabled"
    const val KEY_HAPTICS_ENABLED = "haptics_enabled"
    const val KEY_CHAT_ANIMATIONS_ENABLED = "chat_animations_enabled"
    const val KEY_NAV_TRANSITIONS_ENABLED = "nav_transitions_enabled"
    const val KEY_SCREENSHOT_DETECT_ENABLED = "screenshot_detect_enabled"
    const val KEY_RECENTS_EXCLUSION_ENABLED = "recents_exclusion_enabled"
    const val KEY_SECRET_COPY_BLOCK_ENABLED = "secret_copy_block_enabled"
    const val KEY_SECRET_MEDIA_EXPORT_BLOCK_ENABLED = "secret_media_export_block_enabled"
    const val KEY_SECRET_FORWARD_BLOCK_ENABLED = "secret_forward_block_enabled"
    const val KEY_SECRET_CHAT_EXPORT_BLOCK_ENABLED = "secret_chat_export_block_enabled"
    const val KEY_VISIBLE_WATERMARK_ENABLED = "visible_watermark_enabled"
    const val KEY_SECRET_AUTO_DISAPPEAR_ENABLED = "secret_auto_disappear_enabled"
    const val KEY_SECRET_LINK_PREVIEW_BLOCK_ENABLED = "secret_link_preview_block_enabled"
    const val KEY_SECRET_EXTERNAL_LINK_BLOCK_ENABLED = "secret_external_link_block_enabled"
    const val KEY_SECRET_NOTIF_PREVIEW_BLOCK_ENABLED = "secret_notif_preview_block_enabled"
    const val KEY_SECRET_LIST_PREVIEW_BLOCK_ENABLED = "secret_list_preview_block_enabled"
    const val KEY_SECRET_REACTION_BLOCK_ENABLED = "secret_reaction_block_enabled"
    const val KEY_SECRET_STAR_BLOCK_ENABLED = "secret_star_block_enabled"
    const val KEY_SECRET_TYPING_BLOCK_ENABLED = "secret_typing_block_enabled"
    const val KEY_SECRET_READ_RECEIPT_BLOCK_ENABLED = "secret_read_receipt_block_enabled"
    const val KEY_SECRET_PRESENCE_BLOCK_ENABLED = "secret_presence_block_enabled"
    const val KEY_SECRET_LAST_SEEN_BLOCK_ENABLED = "secret_last_seen_block_enabled"

    // ═══ B2 密聊防泄漏扩展（Surface #71–#78，health 名 burnz/ttlz/fwlz/simz/2faz/ndz/dvz/sntz）═══
    const val KEY_SECRET_SCREENSHOT_BURN_ENABLED = "secret_screenshot_burn_enabled"
    const val KEY_SECRET_AUTO_DESTROY_ENABLED = "secret_auto_destroy_enabled"
    const val KEY_SECRET_FORWARD_WHITELIST_ENABLED = "secret_forward_whitelist_enabled"
    const val KEY_SECRET_SIM_CHANGE_PROTECTION_ENABLED = "secret_sim_change_protection_enabled"
    const val KEY_SECRET_2FA_GATE_ENABLED = "secret_2fa_gate_enabled"
    const val KEY_SECRET_NEW_DEVICE_RISK_ENABLED = "secret_new_device_risk_enabled"
    const val KEY_SECRET_DEVICE_VERIFY_ENABLED = "secret_device_verify_enabled"
    const val KEY_SECRET_SESSION_NOTICE_ENABLED = "secret_session_notice_enabled"

    private val knownKeys = setOf(
        KEY_ALLOW_REGISTRATION,
        KEY_MAINTENANCE_MODE,
        KEY_MAINTENANCE_MESSAGE,
        KEY_GLOBAL_BANNER,
        KEY_INVITE_ONLY_HINT,
        KEY_MAX_GROUP_SIZE,
        KEY_SEALED_SENDER_ENABLED,
        KEY_ALLOW_BOTS,
        KEY_FORCE_E2EE_BANNER,
        KEY_MAX_MESSAGE_PER_MIN,
        KEY_IP_BLOCKLIST,
        KEY_AI_ENABLED,
        KEY_PUBLIC_ANNOUNCEMENT,
        KEY_PQXDH_PREVIEW,
        KEY_MIN_APP_VERSION,
        KEY_SECRET_CHAT_REQUIRED,
        KEY_MAX_BOTS_PER_USER,
        KEY_CAPTURE_ALERT_ENABLED,
        KEY_MEDIA_UPLOAD_ENABLED,
        KEY_GROUP_PLAY_ENABLED,
        KEY_LINK_PREVIEW_ENABLED,
        KEY_VOICE_MESSAGES_ENABLED,
        KEY_REACTIONS_ENABLED,
        KEY_STICKERS_ENABLED,
        KEY_SILENT_SEND_ENABLED,
        KEY_CALLS_ENABLED,
        KEY_SCHEDULED_MESSAGES_ENABLED,
        KEY_VIEW_ONCE_ENABLED,
        KEY_LIVE_LOCATION_ENABLED,
        KEY_MARKDOWN_ENABLED,
        KEY_TYPING_INDICATORS_ENABLED,
        KEY_READ_RECEIPTS_ENABLED,
        KEY_PRESENCE_ENABLED,
        KEY_MESSAGE_STARRING_ENABLED,
        KEY_CHAT_EXPORT_ENABLED,
        KEY_MESSAGE_FORWARDING_ENABLED,
        KEY_GLOBAL_SEARCH_ENABLED,
        KEY_FRIEND_REQUESTS_ENABLED,
        KEY_CHAT_FOLDERS_ENABLED,
        KEY_POSTS_ENABLED,
        KEY_BLOCK_REPORT_ENABLED,
        KEY_CHANNELS_ENABLED,
        KEY_CHAT_ARCHIVE_ENABLED,
        KEY_NEARBY_ENABLED,
        KEY_CHAT_PIN_ENABLED,
        KEY_MARKED_UNREAD_ENABLED,
        KEY_CHAT_MUTE_ENABLED,
        KEY_DISAPPEARING_MESSAGES_ENABLED,
        KEY_CHAT_LOCK_ENABLED,
        KEY_MESSAGE_EDIT_ENABLED,
        KEY_MESSAGE_PIN_ENABLED,
        KEY_MESSAGE_REVOKE_ENABLED,
        KEY_POLLS_ENABLED,
        KEY_APP_LOCK_ENABLED,
        KEY_CHAT_DRAFTS_ENABLED,
        KEY_AI_TRANSLATE_ENABLED,
        KEY_GROUP_INVITES_ENABLED,
        KEY_MENTIONS_ENABLED,
        KEY_NUDGE_ENABLED,
        KEY_SAFETY_CODE_ENABLED,
        KEY_QR_CODE_ENABLED,
        KEY_CONTACT_CARD_ENABLED,
        KEY_SPOILER_MEDIA_ENABLED,
        KEY_AUTO_DOWNLOAD_ENABLED,
        KEY_STATIC_LOCATION_ENABLED,
        KEY_FILE_SHARE_ENABLED,
        KEY_SECRET_CHAT_ENABLED,
        KEY_SCREEN_SECURE_RUNTIME_ENABLED,
        KEY_IMAGE_SEND_ENABLED,
        KEY_VIDEO_SEND_ENABLED,
        KEY_AI_SUMMARY_ENABLED,
        KEY_AI_REWRITE_ENABLED,
        KEY_AI_SUGGEST_REPLIES_ENABLED,
        KEY_AI_TRANSCRIBE_ENABLED,
        KEY_AI_ANALYZE_IMAGE_ENABLED,
        KEY_AI_GROUP_ASSISTANT_ENABLED,
        KEY_AI_ANALYZE_FILE_ENABLED,
        KEY_AI_SEMANTIC_SEARCH_ENABLED,
        KEY_AI_DAILY_TOKEN_BUDGET_PER_USER,
        KEY_AI_CACHE_ENABLED,
        KEY_AI_RETRY_ENABLED,
        KEY_AI_MULTI_MODEL_ENABLED,
        KEY_GIF_SEND_ENABLED,
        KEY_BLIND_WATERMARK_ENABLED,
        KEY_VOICE_CALL_ENABLED,
        KEY_VIDEO_CALL_ENABLED,
        KEY_CHAT_WALLPAPER_ENABLED,
        KEY_CHAT_FONT_SCALE_ENABLED,
        KEY_UNREAD_PRIORITY_ENABLED,
        KEY_RINGTONE_ENABLED,
        KEY_NOTIFICATION_SOUND_ENABLED,
        KEY_NOTIFICATION_PREVIEW_ENABLED,
        KEY_PUSH_NOTIFICATIONS_ENABLED,
        KEY_TASK_REMINDERS_ENABLED,
        KEY_DND_ENABLED,
        KEY_OFFLINE_AI_ENABLED,
        KEY_IN_APP_SOUNDS_ENABLED,
        KEY_HAPTICS_ENABLED,
        KEY_CHAT_ANIMATIONS_ENABLED,
        KEY_NAV_TRANSITIONS_ENABLED,
        KEY_SCREENSHOT_DETECT_ENABLED,
        KEY_RECENTS_EXCLUSION_ENABLED,
        KEY_SECRET_COPY_BLOCK_ENABLED,
        KEY_SECRET_MEDIA_EXPORT_BLOCK_ENABLED,
        KEY_SECRET_FORWARD_BLOCK_ENABLED,
        KEY_SECRET_CHAT_EXPORT_BLOCK_ENABLED,
        KEY_VISIBLE_WATERMARK_ENABLED,
        KEY_SECRET_AUTO_DISAPPEAR_ENABLED,
        KEY_SECRET_LINK_PREVIEW_BLOCK_ENABLED,
        KEY_SECRET_EXTERNAL_LINK_BLOCK_ENABLED,
        KEY_SECRET_NOTIF_PREVIEW_BLOCK_ENABLED,
        KEY_SECRET_LIST_PREVIEW_BLOCK_ENABLED,
        KEY_SECRET_REACTION_BLOCK_ENABLED,
        KEY_SECRET_STAR_BLOCK_ENABLED,
        KEY_SECRET_TYPING_BLOCK_ENABLED,
        KEY_SECRET_READ_RECEIPT_BLOCK_ENABLED,
        KEY_SECRET_PRESENCE_BLOCK_ENABLED,
        KEY_SECRET_LAST_SEEN_BLOCK_ENABLED,
        // B2 密聊防泄漏扩展（Surface #71–#78）
        KEY_SECRET_AUTO_DESTROY_ENABLED,
        KEY_SECRET_SCREENSHOT_BURN_ENABLED,
        KEY_SECRET_FORWARD_WHITELIST_ENABLED,
        KEY_SECRET_SIM_CHANGE_PROTECTION_ENABLED,
        KEY_SECRET_2FA_GATE_ENABLED,
        KEY_SECRET_NEW_DEVICE_RISK_ENABLED,
        KEY_SECRET_DEVICE_VERIFY_ENABLED,
        KEY_SECRET_SESSION_NOTICE_ENABLED
    )

    fun defaults(): Map<String, String> = mapOf(
        KEY_ALLOW_REGISTRATION to ServerConfig.allowRegistration.toString(),
        KEY_MAINTENANCE_MODE to "false",
        KEY_MAINTENANCE_MESSAGE to "System under maintenance. Please try again later.",
        KEY_GLOBAL_BANNER to "",
        KEY_INVITE_ONLY_HINT to "Registration is temporarily closed.",
        KEY_MAX_GROUP_SIZE to "200",
        KEY_SEALED_SENDER_ENABLED to "true",
        KEY_ALLOW_BOTS to "true",
        KEY_FORCE_E2EE_BANNER to "",
        KEY_MAX_MESSAGE_PER_MIN to "60",
        KEY_IP_BLOCKLIST to "",
        KEY_AI_ENABLED to "true",
        KEY_PUBLIC_ANNOUNCEMENT to "",
        KEY_PQXDH_PREVIEW to "false",
        KEY_MIN_APP_VERSION to "0",
        KEY_SECRET_CHAT_REQUIRED to "false",
        KEY_MAX_BOTS_PER_USER to "20",
        KEY_CAPTURE_ALERT_ENABLED to "true",
        KEY_MEDIA_UPLOAD_ENABLED to "true",
        KEY_GROUP_PLAY_ENABLED to "true",
        KEY_LINK_PREVIEW_ENABLED to "true",
        KEY_VOICE_MESSAGES_ENABLED to "true",
        KEY_REACTIONS_ENABLED to "true",
        KEY_STICKERS_ENABLED to "true",
        KEY_SILENT_SEND_ENABLED to "true",
        KEY_CALLS_ENABLED to "true",
        KEY_SCHEDULED_MESSAGES_ENABLED to "true",
        KEY_VIEW_ONCE_ENABLED to "true",
        KEY_LIVE_LOCATION_ENABLED to "true",
        KEY_MARKDOWN_ENABLED to "true",
        KEY_TYPING_INDICATORS_ENABLED to "true",
        KEY_READ_RECEIPTS_ENABLED to "true",
        KEY_PRESENCE_ENABLED to "true",
        KEY_MESSAGE_STARRING_ENABLED to "true",
        KEY_CHAT_EXPORT_ENABLED to "true",
        KEY_MESSAGE_FORWARDING_ENABLED to "true",
        KEY_GLOBAL_SEARCH_ENABLED to "true",
        KEY_FRIEND_REQUESTS_ENABLED to "true",
        KEY_CHAT_FOLDERS_ENABLED to "true",
        KEY_POSTS_ENABLED to "true",
        KEY_BLOCK_REPORT_ENABLED to "true",
        KEY_CHANNELS_ENABLED to "true",
        KEY_CHAT_ARCHIVE_ENABLED to "true",
        KEY_NEARBY_ENABLED to "true",
        KEY_CHAT_PIN_ENABLED to "true",
        KEY_MARKED_UNREAD_ENABLED to "true",
        KEY_CHAT_MUTE_ENABLED to "true",
        KEY_DISAPPEARING_MESSAGES_ENABLED to "true",
        KEY_CHAT_LOCK_ENABLED to "true",
        KEY_MESSAGE_EDIT_ENABLED to "true",
        KEY_MESSAGE_PIN_ENABLED to "true",
        KEY_MESSAGE_REVOKE_ENABLED to "true",
        KEY_POLLS_ENABLED to "true",
        KEY_APP_LOCK_ENABLED to "true",
        KEY_CHAT_DRAFTS_ENABLED to "true",
        KEY_AI_TRANSLATE_ENABLED to "true",
        KEY_GROUP_INVITES_ENABLED to "true",
        KEY_MENTIONS_ENABLED to "true",
        KEY_NUDGE_ENABLED to "true",
        KEY_SAFETY_CODE_ENABLED to "true",
        KEY_QR_CODE_ENABLED to "true",
        KEY_CONTACT_CARD_ENABLED to "true",
        KEY_SPOILER_MEDIA_ENABLED to "true",
        KEY_AUTO_DOWNLOAD_ENABLED to "true",
        KEY_STATIC_LOCATION_ENABLED to "true",
        KEY_FILE_SHARE_ENABLED to "true",
        KEY_SECRET_CHAT_ENABLED to "true",
        KEY_SCREEN_SECURE_RUNTIME_ENABLED to "true",
        KEY_IMAGE_SEND_ENABLED to "true",
        KEY_VIDEO_SEND_ENABLED to "true",
        KEY_AI_SUMMARY_ENABLED to "true",
        KEY_AI_REWRITE_ENABLED to "true",
        KEY_AI_SUGGEST_REPLIES_ENABLED to "true",
        KEY_AI_TRANSCRIBE_ENABLED to "true",
        KEY_AI_ANALYZE_IMAGE_ENABLED to "true",
        KEY_AI_GROUP_ASSISTANT_ENABLED to "true",
        KEY_AI_ANALYZE_FILE_ENABLED to "true",
        KEY_AI_SEMANTIC_SEARCH_ENABLED to "true",
        KEY_AI_DAILY_TOKEN_BUDGET_PER_USER to "200000",
        KEY_AI_CACHE_ENABLED to "true",
        KEY_AI_RETRY_ENABLED to "true",
        KEY_AI_MULTI_MODEL_ENABLED to "true",
        KEY_GIF_SEND_ENABLED to "true",
        KEY_BLIND_WATERMARK_ENABLED to "true",
        KEY_VOICE_CALL_ENABLED to "true",
        KEY_VIDEO_CALL_ENABLED to "true",
        KEY_CHAT_WALLPAPER_ENABLED to "true",
        KEY_CHAT_FONT_SCALE_ENABLED to "true",
        KEY_UNREAD_PRIORITY_ENABLED to "true",
        KEY_RINGTONE_ENABLED to "true",
        KEY_NOTIFICATION_SOUND_ENABLED to "true",
        KEY_NOTIFICATION_PREVIEW_ENABLED to "true",
        KEY_PUSH_NOTIFICATIONS_ENABLED to "true",
        KEY_TASK_REMINDERS_ENABLED to "true",
        KEY_DND_ENABLED to "true",
        KEY_OFFLINE_AI_ENABLED to "true",
        KEY_IN_APP_SOUNDS_ENABLED to "true",
        KEY_HAPTICS_ENABLED to "true",
        KEY_CHAT_ANIMATIONS_ENABLED to "true",
        KEY_NAV_TRANSITIONS_ENABLED to "true",
        KEY_SCREENSHOT_DETECT_ENABLED to "true",
        KEY_RECENTS_EXCLUSION_ENABLED to "true",
        KEY_SECRET_COPY_BLOCK_ENABLED to "true",
        KEY_SECRET_MEDIA_EXPORT_BLOCK_ENABLED to "true",
        KEY_SECRET_FORWARD_BLOCK_ENABLED to "true",
        KEY_SECRET_CHAT_EXPORT_BLOCK_ENABLED to "true",
        KEY_VISIBLE_WATERMARK_ENABLED to "true",
        KEY_SECRET_AUTO_DISAPPEAR_ENABLED to "true",
        KEY_SECRET_LINK_PREVIEW_BLOCK_ENABLED to "true",
        KEY_SECRET_EXTERNAL_LINK_BLOCK_ENABLED to "false",
        KEY_SECRET_NOTIF_PREVIEW_BLOCK_ENABLED to "true",
        KEY_SECRET_LIST_PREVIEW_BLOCK_ENABLED to "true",
        KEY_SECRET_REACTION_BLOCK_ENABLED to "true",
        KEY_SECRET_STAR_BLOCK_ENABLED to "true",
        KEY_SECRET_TYPING_BLOCK_ENABLED to "true",
        KEY_SECRET_READ_RECEIPT_BLOCK_ENABLED to "true",
        KEY_SECRET_PRESENCE_BLOCK_ENABLED to "true",
        KEY_SECRET_LAST_SEEN_BLOCK_ENABLED to "true",
        // B2 密聊防泄漏扩展（Surface #71–#78）
        KEY_SECRET_AUTO_DESTROY_ENABLED to "true",
        KEY_SECRET_SCREENSHOT_BURN_ENABLED to "true",
        KEY_SECRET_FORWARD_WHITELIST_ENABLED to "true",
        KEY_SECRET_SIM_CHANGE_PROTECTION_ENABLED to "true",
        KEY_SECRET_2FA_GATE_ENABLED to "false",
        KEY_SECRET_NEW_DEVICE_RISK_ENABLED to "true",
        KEY_SECRET_DEVICE_VERIFY_ENABLED to "true",
        KEY_SECRET_SESSION_NOTICE_ENABLED to "true"
    )

    fun all(): Map<String, String> {
        refreshIfStale()
        val base = defaults().toMutableMap()
        base.putAll(cache)
        return base
    }

    fun get(key: String): String {
        refreshIfStale()
        return cache[key] ?: defaults()[key].orEmpty()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        val raw = get(key).trim().lowercase()
        return when (raw) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> default
        }
    }

    fun getInt(key: String, default: Int): Int =
        get(key).toIntOrNull() ?: default

    fun getLong(key: String, default: Long): Long =
        get(key).toLongOrNull() ?: default

    fun isRegistrationAllowed(): Boolean =
        getBoolean(KEY_ALLOW_REGISTRATION, ServerConfig.allowRegistration)

    fun isMaintenanceMode(): Boolean =
        getBoolean(KEY_MAINTENANCE_MODE, false)

    fun isSealedSenderEnabled(): Boolean =
        getBoolean(KEY_SEALED_SENDER_ENABLED, true)

    fun isBotsAllowed(): Boolean =
        getBoolean(KEY_ALLOW_BOTS, true)

    fun maxMessagePerMinute(): Int =
        getInt(KEY_MAX_MESSAGE_PER_MIN, 60).coerceIn(10, 600)

    fun isAiEnabled(): Boolean =
        getBoolean(KEY_AI_ENABLED, true)

    fun ipBlocklist(): Set<String> =
        get(KEY_IP_BLOCKLIST)
            .split(",", "\n", ";", " ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    fun isPqxdhPreviewEnabled(): Boolean =
        getBoolean(KEY_PQXDH_PREVIEW, false)

    fun minAppVersion(): String = get(KEY_MIN_APP_VERSION).ifBlank { "0" }

    fun isSecretChatRequired(): Boolean =
        getBoolean(KEY_SECRET_CHAT_REQUIRED, false)

    fun maxBotsPerUser(): Int =
        getInt(KEY_MAX_BOTS_PER_USER, 20).coerceIn(1, 200)

    fun isCaptureAlertEnabled(): Boolean =
        getBoolean(KEY_CAPTURE_ALERT_ENABLED, true)

    fun isMediaUploadEnabled(): Boolean =
        getBoolean(KEY_MEDIA_UPLOAD_ENABLED, true)

    fun isGroupPlayEnabled(): Boolean =
        getBoolean(KEY_GROUP_PLAY_ENABLED, true)

    fun isLinkPreviewEnabled(): Boolean =
        getBoolean(KEY_LINK_PREVIEW_ENABLED, true)

    fun isVoiceMessagesEnabled(): Boolean =
        getBoolean(KEY_VOICE_MESSAGES_ENABLED, true)

    fun isReactionsEnabled(): Boolean =
        getBoolean(KEY_REACTIONS_ENABLED, true)

    fun isStickersEnabled(): Boolean =
        getBoolean(KEY_STICKERS_ENABLED, true)

    fun isSilentSendEnabled(): Boolean =
        getBoolean(KEY_SILENT_SEND_ENABLED, true)

    fun isCallsEnabled(): Boolean =
        getBoolean(KEY_CALLS_ENABLED, true)

    fun isScheduledMessagesEnabled(): Boolean =
        getBoolean(KEY_SCHEDULED_MESSAGES_ENABLED, true)

    fun isViewOnceEnabled(): Boolean =
        getBoolean(KEY_VIEW_ONCE_ENABLED, true)

    fun isLiveLocationEnabled(): Boolean =
        getBoolean(KEY_LIVE_LOCATION_ENABLED, true)

    fun isMarkdownEnabled(): Boolean =
        getBoolean(KEY_MARKDOWN_ENABLED, true)

    fun isTypingIndicatorsEnabled(): Boolean =
        getBoolean(KEY_TYPING_INDICATORS_ENABLED, true)

    fun isReadReceiptsEnabled(): Boolean =
        getBoolean(KEY_READ_RECEIPTS_ENABLED, true)

    fun isPresenceEnabled(): Boolean =
        getBoolean(KEY_PRESENCE_ENABLED, true)

    fun isMessageStarringEnabled(): Boolean =
        getBoolean(KEY_MESSAGE_STARRING_ENABLED, true)

    fun isChatExportEnabled(): Boolean =
        getBoolean(KEY_CHAT_EXPORT_ENABLED, true)

    fun isMessageForwardingEnabled(): Boolean =
        getBoolean(KEY_MESSAGE_FORWARDING_ENABLED, true)

    fun isGlobalSearchEnabled(): Boolean =
        getBoolean(KEY_GLOBAL_SEARCH_ENABLED, true)

    fun isFriendRequestsEnabled(): Boolean =
        getBoolean(KEY_FRIEND_REQUESTS_ENABLED, true)

    fun isChatFoldersEnabled(): Boolean =
        getBoolean(KEY_CHAT_FOLDERS_ENABLED, true)

    fun isPostsEnabled(): Boolean =
        getBoolean(KEY_POSTS_ENABLED, true)

    fun isBlockReportEnabled(): Boolean =
        getBoolean(KEY_BLOCK_REPORT_ENABLED, true)

    fun isChannelsEnabled(): Boolean =
        getBoolean(KEY_CHANNELS_ENABLED, true)

    fun isChatArchiveEnabled(): Boolean =
        getBoolean(KEY_CHAT_ARCHIVE_ENABLED, true)

    fun isNearbyEnabled(): Boolean =
        getBoolean(KEY_NEARBY_ENABLED, true)

    fun isChatPinEnabled(): Boolean =
        getBoolean(KEY_CHAT_PIN_ENABLED, true)

    fun isMarkedUnreadEnabled(): Boolean =
        getBoolean(KEY_MARKED_UNREAD_ENABLED, true)

    fun isChatMuteEnabled(): Boolean =
        getBoolean(KEY_CHAT_MUTE_ENABLED, true)

    fun isDisappearingMessagesEnabled(): Boolean =
        getBoolean(KEY_DISAPPEARING_MESSAGES_ENABLED, true)

    fun isChatLockEnabled(): Boolean =
        getBoolean(KEY_CHAT_LOCK_ENABLED, true)

    fun isMessageEditEnabled(): Boolean =
        getBoolean(KEY_MESSAGE_EDIT_ENABLED, true)

    fun isMessagePinEnabled(): Boolean =
        getBoolean(KEY_MESSAGE_PIN_ENABLED, true)

    fun isMessageRevokeEnabled(): Boolean =
        getBoolean(KEY_MESSAGE_REVOKE_ENABLED, true)

    fun isPollsEnabled(): Boolean =
        getBoolean(KEY_POLLS_ENABLED, true)

    fun isAppLockEnabled(): Boolean =
        getBoolean(KEY_APP_LOCK_ENABLED, true)

    fun isChatDraftsEnabled(): Boolean =
        getBoolean(KEY_CHAT_DRAFTS_ENABLED, true)

    fun isAiTranslateEnabled(): Boolean =
        getBoolean(KEY_AI_TRANSLATE_ENABLED, true)

    fun isGroupInvitesEnabled(): Boolean =
        getBoolean(KEY_GROUP_INVITES_ENABLED, true)

    fun isMentionsEnabled(): Boolean =
        getBoolean(KEY_MENTIONS_ENABLED, true)

    fun isNudgeEnabled(): Boolean =
        getBoolean(KEY_NUDGE_ENABLED, true)

    fun isSafetyCodeEnabled(): Boolean =
        getBoolean(KEY_SAFETY_CODE_ENABLED, true)

    fun isQrCodeEnabled(): Boolean =
        getBoolean(KEY_QR_CODE_ENABLED, true)

    fun isContactCardEnabled(): Boolean =
        getBoolean(KEY_CONTACT_CARD_ENABLED, true)

    fun isSpoilerMediaEnabled(): Boolean =
        getBoolean(KEY_SPOILER_MEDIA_ENABLED, true)

    fun isAutoDownloadEnabled(): Boolean =
        getBoolean(KEY_AUTO_DOWNLOAD_ENABLED, true)

    fun isStaticLocationEnabled(): Boolean =
        getBoolean(KEY_STATIC_LOCATION_ENABLED, true)

    fun isFileShareEnabled(): Boolean =
        getBoolean(KEY_FILE_SHARE_ENABLED, true)

    fun isSecretChatEnabled(): Boolean =
        getBoolean(KEY_SECRET_CHAT_ENABLED, true)

    fun isScreenSecureRuntimeEnabled(): Boolean =
        getBoolean(KEY_SCREEN_SECURE_RUNTIME_ENABLED, true)

    fun isImageSendEnabled(): Boolean =
        getBoolean(KEY_IMAGE_SEND_ENABLED, true)

    fun isVideoSendEnabled(): Boolean =
        getBoolean(KEY_VIDEO_SEND_ENABLED, true)

    fun isAiSummaryEnabled(): Boolean =
        getBoolean(KEY_AI_SUMMARY_ENABLED, true)

    fun isAiRewriteEnabled(): Boolean =
        getBoolean(KEY_AI_REWRITE_ENABLED, true)

    fun isAiSuggestRepliesEnabled(): Boolean =
        getBoolean(KEY_AI_SUGGEST_REPLIES_ENABLED, true)

    fun isAiTranscribeEnabled(): Boolean =
        getBoolean(KEY_AI_TRANSCRIBE_ENABLED, true)

    fun isAiAnalyzeImageEnabled(): Boolean =
        getBoolean(KEY_AI_ANALYZE_IMAGE_ENABLED, true)

    fun isAiGroupAssistantEnabled(): Boolean =
        getBoolean(KEY_AI_GROUP_ASSISTANT_ENABLED, true)

    fun isAiAnalyzeFileEnabled(): Boolean =
        getBoolean(KEY_AI_ANALYZE_FILE_ENABLED, true)

    fun isAiSemanticSearchEnabled(): Boolean =
        getBoolean(KEY_AI_SEMANTIC_SEARCH_ENABLED, true)

    /** 每用户每日 token 预算（input + output）。超限时网关应返回 BudgetExceeded。 */
    fun aiDailyTokenBudgetPerUser(): Long =
        getLong(KEY_AI_DAILY_TOKEN_BUDGET_PER_USER, 200_000L).coerceIn(0L, 1_000_000_000L)

    /** 是否启用 AI 网关内的幂等缓存（translate / summarize）。 */
    fun isAiCacheEnabled(): Boolean =
        getBoolean(KEY_AI_CACHE_ENABLED, true)

    /** 是否启用 AI 网关的瞬态错误重试与退避。 */
    fun isAiRetryEnabled(): Boolean =
        getBoolean(KEY_AI_RETRY_ENABLED, true)

    /** 是否启用按任务类型的多模型路由 + 兜底模型回退。 */
    fun isAiMultiModelEnabled(): Boolean =
        getBoolean(KEY_AI_MULTI_MODEL_ENABLED, true)

    fun isGifSendEnabled(): Boolean =
        getBoolean(KEY_GIF_SEND_ENABLED, true)

    fun isBlindWatermarkEnabled(): Boolean =
        getBoolean(KEY_BLIND_WATERMARK_ENABLED, true)

    fun isVoiceCallEnabled(): Boolean =
        getBoolean(KEY_VOICE_CALL_ENABLED, true)

    fun isVideoCallEnabled(): Boolean =
        getBoolean(KEY_VIDEO_CALL_ENABLED, true)

    fun isChatWallpaperEnabled(): Boolean =
        getBoolean(KEY_CHAT_WALLPAPER_ENABLED, true)

    fun isChatFontScaleEnabled(): Boolean =
        getBoolean(KEY_CHAT_FONT_SCALE_ENABLED, true)

    fun isUnreadPriorityEnabled(): Boolean =
        getBoolean(KEY_UNREAD_PRIORITY_ENABLED, true)

    fun isRingtoneEnabled(): Boolean =
        getBoolean(KEY_RINGTONE_ENABLED, true)

    fun isNotificationSoundEnabled(): Boolean =
        getBoolean(KEY_NOTIFICATION_SOUND_ENABLED, true)

    fun isNotificationPreviewEnabled(): Boolean =
        getBoolean(KEY_NOTIFICATION_PREVIEW_ENABLED, true)

    fun isPushNotificationsEnabled(): Boolean =
        getBoolean(KEY_PUSH_NOTIFICATIONS_ENABLED, true)

    fun isTaskRemindersEnabled(): Boolean =
        getBoolean(KEY_TASK_REMINDERS_ENABLED, true)

    fun isDndEnabled(): Boolean =
        getBoolean(KEY_DND_ENABLED, true)

    fun isOfflineAiEnabled(): Boolean =
        getBoolean(KEY_OFFLINE_AI_ENABLED, true)

    fun isInAppSoundsEnabled(): Boolean =
        getBoolean(KEY_IN_APP_SOUNDS_ENABLED, true)

    fun isHapticsEnabled(): Boolean =
        getBoolean(KEY_HAPTICS_ENABLED, true)

    fun isChatAnimationsEnabled(): Boolean =
        getBoolean(KEY_CHAT_ANIMATIONS_ENABLED, true)

    fun isNavTransitionsEnabled(): Boolean =
        getBoolean(KEY_NAV_TRANSITIONS_ENABLED, true)

    fun isScreenshotDetectEnabled(): Boolean =
        getBoolean(KEY_SCREENSHOT_DETECT_ENABLED, true)

    fun isRecentsExclusionEnabled(): Boolean =
        getBoolean(KEY_RECENTS_EXCLUSION_ENABLED, true)

    fun isSecretCopyBlockEnabled(): Boolean =
        getBoolean(KEY_SECRET_COPY_BLOCK_ENABLED, true)

    fun isSecretMediaExportBlockEnabled(): Boolean =
        getBoolean(KEY_SECRET_MEDIA_EXPORT_BLOCK_ENABLED, true)

    fun isSecretForwardBlockEnabled(): Boolean =
        getBoolean(KEY_SECRET_FORWARD_BLOCK_ENABLED, true)

    fun isSecretChatExportBlockEnabled(): Boolean =
        getBoolean(KEY_SECRET_CHAT_EXPORT_BLOCK_ENABLED, true)

    fun isVisibleWatermarkEnabled(): Boolean =
        getBoolean(KEY_VISIBLE_WATERMARK_ENABLED, true)

    fun isSecretAutoDisappearEnabled(): Boolean =
        getBoolean(KEY_SECRET_AUTO_DISAPPEAR_ENABLED, true)

    fun isSecretLinkPreviewBlockEnabled(): Boolean =
        getBoolean(KEY_SECRET_LINK_PREVIEW_BLOCK_ENABLED, true)

    fun isSecretExternalLinkBlockEnabled(): Boolean =
        getBoolean(KEY_SECRET_EXTERNAL_LINK_BLOCK_ENABLED, false)

    fun isSecretNotifPreviewBlockEnabled(): Boolean =
        getBoolean(KEY_SECRET_NOTIF_PREVIEW_BLOCK_ENABLED, true)

    fun isSecretListPreviewBlockEnabled(): Boolean =
        getBoolean(KEY_SECRET_LIST_PREVIEW_BLOCK_ENABLED, true)

    fun isSecretReactionBlockEnabled(): Boolean =
        getBoolean(KEY_SECRET_REACTION_BLOCK_ENABLED, true)

    fun isSecretStarBlockEnabled(): Boolean =
        getBoolean(KEY_SECRET_STAR_BLOCK_ENABLED, true)

    fun isSecretTypingBlockEnabled(): Boolean =
        getBoolean(KEY_SECRET_TYPING_BLOCK_ENABLED, true)

    fun isSecretReadReceiptBlockEnabled(): Boolean =
        getBoolean(KEY_SECRET_READ_RECEIPT_BLOCK_ENABLED, true)

    fun isSecretPresenceBlockEnabled(): Boolean =
        getBoolean(KEY_SECRET_PRESENCE_BLOCK_ENABLED, true)

    fun isSecretLastSeenBlockEnabled(): Boolean =
        getBoolean(KEY_SECRET_LAST_SEEN_BLOCK_ENABLED, true)

    // ═══ B2 密聊防泄漏扩展（Surface #71–#78）═══
    // 这些是「客户端能力门」：仅控制客户端是否展示/执行对应本地防护，
    // 服务端只存开关位，不接触密聊明文（密聊内容全程 E2EE）。
    fun isSecretScreenshotBurnEnabled(): Boolean =
        getBoolean(KEY_SECRET_SCREENSHOT_BURN_ENABLED, true)

    fun isSecretAutoDestroyEnabled(): Boolean =
        getBoolean(KEY_SECRET_AUTO_DESTROY_ENABLED, true)

    fun isSecretForwardWhitelistEnabled(): Boolean =
        getBoolean(KEY_SECRET_FORWARD_WHITELIST_ENABLED, true)

    fun isSecretSimChangeProtectionEnabled(): Boolean =
        getBoolean(KEY_SECRET_SIM_CHANGE_PROTECTION_ENABLED, true)

    fun isSecret2faGateEnabled(): Boolean =
        getBoolean(KEY_SECRET_2FA_GATE_ENABLED, false)

    fun isSecretNewDeviceRiskEnabled(): Boolean =
        getBoolean(KEY_SECRET_NEW_DEVICE_RISK_ENABLED, true)

    fun isSecretDeviceVerifyEnabled(): Boolean =
        getBoolean(KEY_SECRET_DEVICE_VERIFY_ENABLED, true)

    fun isSecretSessionNoticeEnabled(): Boolean =
        getBoolean(KEY_SECRET_SESSION_NOTICE_ENABLED, true)

    fun maxGroupSize(): Int =
        getInt(KEY_MAX_GROUP_SIZE, 200).coerceIn(2, 5000)

    fun isIpBlocked(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        val list = ipBlocklist()
        if (list.isEmpty()) return false
        if (ip in list) return true
        // simple prefix match for CIDR-ish "1.2.3." entries
        return list.any { rule -> rule.endsWith(".") && ip.startsWith(rule) }
    }

    fun set(key: String, value: String, actorId: String?): Boolean {
        // 纵深防御：即便未来有其他调用方忘记在路由层鉴权，非主管理员（或匿名）也不得改写运行时配置。
        if (actorId == null || !AdminAccess.isAdmin(actorId)) return false
        if (key !in knownKeys) return false
        val cleaned = value.trim().take(4_000)
        val now = System.currentTimeMillis()
        transaction {
            SystemSettings.upsert(SystemSettings.key) {
                it[SystemSettings.key] = key
                it[SystemSettings.value] = cleaned
                it[updatedAt] = now
                it[updatedBy] = actorId
            }
        }
        cache[key] = cleaned
        loadedAt.set(now)
        return true
    }

    fun setMany(values: Map<String, String>, actorId: String?): Map<String, String> {
        val applied = linkedMapOf<String, String>()
        for ((k, v) in values) {
            if (set(k, v, actorId)) applied[k] = get(k)
        }
        return all()
    }

    fun invalidate() {
        loadedAt.set(0L)
        cache.clear()
    }

    private fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now - loadedAt.get() < CACHE_TTL_MS && cache.isNotEmpty()) return
        synchronized(this) {
            if (now - loadedAt.get() < CACHE_TTL_MS && cache.isNotEmpty()) return
            val rows = runCatching {
                transaction {
                    SystemSettings.selectAll().associate {
                        it[SystemSettings.key] to it[SystemSettings.value]
                    }
                }
            }.getOrDefault(emptyMap())
            cache.clear()
            cache.putAll(rows)
            loadedAt.set(System.currentTimeMillis())
        }
    }
}
