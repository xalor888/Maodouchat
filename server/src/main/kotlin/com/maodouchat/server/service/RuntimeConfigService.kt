package com.maodouchat.server.service

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.db.SystemSettings
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
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
    const val KEY_AI_CONTENT_MODERATION_ENABLED = "ai_content_moderation_enabled"
    const val KEY_PUBLIC_ANNOUNCEMENT = "public_announcement"
    const val KEY_PQXDH_PREVIEW = "pqxdh_preview"
    const val KEY_MIN_APP_VERSION = "min_app_version"
    const val KEY_UPDATE_VERSION_CODE = "update_version_code"
    const val KEY_UPDATE_VERSION_NAME = "update_version_name"
    const val KEY_UPDATE_APK_URL = "update_apk_url"
    const val KEY_UPDATE_APK_SHA256 = "update_apk_sha256"
    const val KEY_UPDATE_SERVER_URL = "update_server_url"
    const val KEY_UPDATE_NOTES = "update_notes"
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
    const val KEY_AI_DAILY_TOKEN_BUDGET_PER_USER = "ai_daily_token_budget_per_user"
    const val KEY_AI_RETRY_ENABLED = "ai_retry_enabled"
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

    /** B13：默认值由 typed registry 派生，单一事实源消除 knownKeys/defaults 三处重复。 */
    fun defaults(): Map<String, String> =
        RuntimeSettingsRegistry.specs.associate { it.key to it.default }

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

    /** GitHub Release 上传成功后只改更新元数据，不走站长 JWT。 */
    fun applyPublishedUpdate(
        versionCode: Int,
        versionName: String,
        apkUrl: String,
        apkSha256: String,
        notes: String,
    ) {
        val now = System.currentTimeMillis()
        val actorId = "github-release"
        val values = mapOf(
            KEY_UPDATE_VERSION_CODE to versionCode.toString(),
            KEY_UPDATE_VERSION_NAME to versionName,
            KEY_UPDATE_APK_URL to apkUrl,
            KEY_UPDATE_APK_SHA256 to apkSha256.lowercase(),
            KEY_UPDATE_NOTES to notes,
        )
        transaction {
            values.forEach { (key, cleaned) ->
                writeSettingInTx(key, cleaned.take(4_000), actorId, now)
            }
        }
        values.forEach { (key, cleaned) -> cache[key] = cleaned.take(4_000) }
        loadedAt.set(now)
    }

    fun set(key: String, value: String, actorId: String?): Boolean {
        // 纵深防御：即便未来有其他调用方忘记在路由层鉴权，非主管理员（或匿名）也不得改写运行时配置。
        if (actorId == null || !AdminAccess.isAdmin(actorId)) return false
        val cleaned = normalize(key, value) ?: return false
        val now = System.currentTimeMillis()
        transaction {
            writeSettingInTx(key, cleaned, actorId, now)
        }
        cache[key] = cleaned
        loadedAt.set(now)
        return true
    }

    /** B13：按 typed registry 校验类型与范围；未知 key 或非法值返回 null，合法值规范化后返回。 */
    internal fun normalize(key: String, value: String): String? {
        val spec = RuntimeSettingsRegistry.spec(key) ?: return null
        val raw = value.trim().take(4_000)
        return when (spec.type) {
            RuntimeSettingType.BOOLEAN -> when (raw.lowercase()) {
                "1", "true", "yes", "on" -> "true"
                "0", "false", "no", "off" -> "false"
                else -> null
            }
            RuntimeSettingType.INT -> raw.toIntOrNull()
                ?.takeIf { (spec.min == null || it >= spec.min) && (spec.max == null || it <= spec.max) }
                ?.toString()
            RuntimeSettingType.LONG -> raw.toLongOrNull()
                ?.takeIf { (spec.min == null || it >= spec.min) && (spec.max == null || it <= spec.max) }
                ?.toString()
            RuntimeSettingType.STRING, RuntimeSettingType.STRING_SET -> raw
        }
    }

    private fun writeSettingInTx(key: String, cleaned: String, actorId: String, now: Long) {
        if (com.maodouchat.server.db.isH2Db()) {
            // H2 2.x 不支持 Exposed 单键 upsert 生成的 MERGE ... USING (VALUES)，用 select→update/insert
            val existing = SystemSettings.selectAll()
                .where { SystemSettings.key eq key }
                .firstOrNull()
            if (existing == null) {
                SystemSettings.insert {
                    it[SystemSettings.key] = key
                    it[SystemSettings.value] = cleaned
                    it[updatedAt] = now
                    it[updatedBy] = actorId
                }
            } else {
                SystemSettings.update({ SystemSettings.key eq key }) {
                    it[SystemSettings.value] = cleaned
                    it[updatedAt] = now
                    it[updatedBy] = actorId
                }
            }
        } else {
            SystemSettings.upsert(SystemSettings.key) {
                it[SystemSettings.key] = key
                it[SystemSettings.value] = cleaned
                it[updatedAt] = now
                it[updatedBy] = actorId
            }
        }
    }

    fun setMany(values: Map<String, String>, actorId: String?): Map<String, String> {
        if (actorId == null || !AdminAccess.isAdmin(actorId)) return all()
        val now = System.currentTimeMillis()
        val valid = values.mapNotNull { (key, value) ->
            normalize(key, value)?.let { key to it }
        }.toMap()
        if (valid.isNotEmpty()) {
            transaction {
                valid.forEach { (key, cleaned) ->
                    writeSettingInTx(key, cleaned, actorId, now)
                }
            }
            valid.forEach { (key, cleaned) -> cache[key] = cleaned }
            loadedAt.set(now)
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
            val rows = try {
                transaction {
                    SystemSettings.selectAll().associate {
                        it[SystemSettings.key] to it[SystemSettings.value]
                    }
                }
            } catch (e: Exception) {
                // DB 瞬时故障不推进 loadedAt、不清旧缓存，下一轮 TTL 仍会重试。
                return
            }
            cache.clear()
            cache.putAll(rows)
            loadedAt.set(System.currentTimeMillis())
        }
    }
}
