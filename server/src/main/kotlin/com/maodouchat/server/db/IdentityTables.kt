package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table

// 身份、会话、设备与客户端偏好表。

object Users : Table("users") {
    val id = varchar("id", 50)
    val name = varchar("name", 100)
    val email = varchar("email", 200).uniqueIndex()
    val passwordHash = varchar("password_hash", 200)
    val avatar = varchar("avatar", 500).nullable()
    val status = varchar("status", 100).default("")
    val isOnline = bool("is_online").default(false)
    val lastSeen = long("last_seen").default(System.currentTimeMillis())
    val showOnline = bool("show_online").default(true)
    /** everyone / contacts / nobody — 在线状态对谁可见 */
    val onlineVisibility = varchar("online_visibility", 20).default("everyone")
    /** 是否对他人展示个性签名 / 自定义状态 */
    val showStatus = bool("show_status").default(true)
    val searchable = bool("searchable").default(true)
    val defaultPostVisibility = varchar("default_post_visibility", 20).default("PUBLIC")
    val accessTokenVersion = long("access_token_version").default(0)
    val isModerator = bool("is_moderator").default(false)
    val suspendedUntil = long("suspended_until").default(0)
    val messageRestrictedUntil = long("message_restricted_until").default(0)
    val postRestrictedUntil = long("post_restricted_until").default(0)
    val deletedAt = long("deleted_at").nullable()
    /** Base32 TOTP secret; null/blank means 2FA disabled. */
    val totpSecret = varchar("totp_secret", 64).nullable()
    val totpEnabled = bool("totp_enabled").default(false)
    /** 8.51 修复 M2：TOTP 已用 counter 持久化（DB 原子 CAS），杜绝重启/多实例重放同一步 code。 */
    val totpLastCounter = long("totp_last_counter").default(0)
    /** 0.75：TOTP 恢复码（BCrypt 哈希，逗号分隔；单次使用，丢失验证器时恢复登录）。 */
    val totpBackupCodes = text("totp_backup_codes").nullable()
    /** 唯一用户名（类似 @username），用于聊天猫个人主页链接 chat.mdou.me/u/{username} */
    val username = varchar("username", 50).nullable().uniqueIndex()
    override val primaryKey = PrimaryKey(id)
}


object AuthSessions : Table("auth_sessions") {
    val id = varchar("id", 80)
    val userId = varchar("user_id", 50)
    val signalDeviceId = integer("signal_device_id").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val revokedAt = long("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_auth_sessions_user_device", false, userId, signalDeviceId)
    }
}


object RefreshTokens : Table("refresh_tokens") {
    val tokenHash = varchar("token_hash", 64)
    val userId = varchar("user_id", 50) references Users.id
    val sessionId = varchar("session_id", 80).default("")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val revokedAt = long("revoked_at").nullable()
    override val primaryKey = PrimaryKey(tokenHash)

    init {
        index("idx_refresh_tokens_user_id", false, userId)
    }
}


object RevokedAccessTokens : Table("revoked_access_tokens") {
    val tokenId = varchar("token_id", 80)
    val userId = varchar("user_id", 50) references Users.id
    val expiresAt = long("expires_at")
    val revokedAt = long("revoked_at")
    override val primaryKey = PrimaryKey(tokenId)

    init {
        index("idx_revoked_access_tokens_user_id", false, userId)
        index("idx_revoked_access_tokens_expires_at", false, expiresAt)
    }
}


object PushTokens : Table("push_tokens") {
    val userId = varchar("user_id", 50) references Users.id
    val deviceId = varchar("device_id", 100)
    val authSessionId = varchar("auth_session_id", 80).nullable()
    val token = varchar("token", 512).uniqueIndex()
    val platform = varchar("platform", 20).default("ANDROID")
    val timezoneOffsetMinutes = integer("timezone_offset_minutes").default(0)
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(userId, deviceId)

    init {
        index("idx_push_tokens_user_id", false, userId)
    }
}


object ClientPrefs : Table("client_prefs") {
    val userId = varchar("user_id", 50) references Users.id
    val themeMode = varchar("theme_mode", 16).default("system")
    // 9.204：主题风格家族（maodou / tg_classic / tg_midnight / tg_graphite），新列启动期自动补齐
    val themeStyle = varchar("theme_style", 24).default("maodou")
    // 9.205：自定义强调色 id（none / blue / green / purple / orange / pink / red / teal）
    val accentColor = varchar("accent_color", 16).default("none")
    val languageMode = varchar("language_mode", 16).default("system")
    val chatWallpaper = varchar("chat_wallpaper", 32).default("default")
    val chatFontScale = varchar("chat_font_scale", 16).default("normal")
    val linkPreviewEnabled = bool("link_preview_enabled").default(true)
    val unreadPriorityEnabled = bool("unread_priority_enabled").default(true)
    val writingStyleEnabled = bool("writing_style_enabled").default(false)
    val writingStylePreset = varchar("writing_style_preset", 40).default("none")
    val writingStyleCustom = varchar("writing_style_custom", 320).default("")
    val appLockTimeoutMinutes = long("app_lock_timeout_minutes").default(5L)
    val screenSecureEnabled = bool("screen_secure_enabled").default(false)
    val sensitiveGateEnabled = bool("sensitive_gate_enabled").default(true)
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(userId)
}


object SystemSettings : Table("system_settings") {
    val key = varchar("key", 64)
    val value = text("value")
    val updatedAt = long("updated_at")
    val updatedBy = varchar("updated_by", 50).nullable()
    override val primaryKey = PrimaryKey(key)
}


object NotificationPreferences : Table("notification_preferences") {
    val userId = varchar("user_id", 50) references Users.id
    val enableNotifications = bool("enable_notifications").default(true)
    val soundEnabled = bool("sound_enabled").default(true)
    val previewEnabled = bool("preview_enabled").default(true)
    val ringtoneEnabled = bool("ringtone_enabled").default(true)
    val dndStartHour = integer("dnd_start_hour").default(22)
    val dndEndHour = integer("dnd_end_hour").default(7)
    val dndEnabled = bool("dnd_enabled").default(false)
    val dndStartMinute = integer("dnd_start_minute").default(22 * 60)
    val dndEndMinute = integer("dnd_end_minute").default(7 * 60)
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(userId)
}
