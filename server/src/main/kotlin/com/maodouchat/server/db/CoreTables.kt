package com.maodouchat.server.db

import com.maodouchat.server.config.ServerConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update

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

object Chats : Table("chats") {
    val id = varchar("id", 50)
    val isGroup = bool("is_group").default(false)
    /** 会话类型：DIRECT / GROUP / CHANNEL / SECRET（密聊独立 1:1）。 */
    val chatType = varchar("chat_type", 20).default("DIRECT")
    val groupName = varchar("group_name", 200).nullable()
    val groupAnnouncement = text("group_announcement").nullable()
    val groupAvatar = varchar("group_avatar", 500).nullable()
    val groupInviteToken = varchar("group_invite_token", 80).nullable().uniqueIndex()
    val groupInviteExpiresAt = long("group_invite_expires_at").default(0)
    val groupInviteMaxUses = integer("group_invite_max_uses").default(0)
    val groupInviteUseCount = integer("group_invite_use_count").default(0)
    val memberRevision = long("member_revision").default(0)
    /** 1:1 阅后即焚时长（秒）；0=关；群聊强制 0 */
    val disappearingMessageSeconds = integer("disappearing_message_seconds").default(0)
    /** 会话最近一条消息预览（服务端缓存，用于列表展示） */
    val lastMessage = varchar("last_message", 200).nullable()
    val lastMessageType = varchar("last_message_type", 20).default("TEXT")
    val lastMessageTime = long("last_message_time").default(0L)
    override val primaryKey = PrimaryKey(id)
}

object GroupAuditLogs : Table("group_audit_logs") {
    val id = varchar("id", 100)
    val chatId = varchar("chat_id", 50) references Chats.id
    val actorId = varchar("actor_id", 50) references Users.id
    val action = varchar("action", 40)
    val targetUserId = varchar("target_user_id", 50).nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)

    init { index("idx_group_audit_chat_created", false, chatId, createdAt) }
}

object ChatParticipants : Table("chat_participants") {
    val chatId = varchar("chat_id", 50) references Chats.id
    val userId = varchar("user_id", 50) references Users.id
    /** 角色：OWNER / ADMIN / MEMBER */
    val role = varchar("role", 20).default("MEMBER")
    /** 群头衔（由群主/管理员设置的自定义标签） */
    val title = varchar("title", 50).nullable()
    /** 我在本群的昵称 */
    val groupNickname = varchar("group_nickname", 100).nullable()
    /** 加入时间 */
    val joinedAt = long("joined_at").default(System.currentTimeMillis())
    /** 禁言截止时间；0 表示未禁言 */
    val mutedUntil = long("muted_until").default(0)
    override val primaryKey = PrimaryKey(chatId, userId)

    // Bug #26: getChatsForUser / shareChat / getChatBetweenUsers 按 userId 查询
    // 主键 (chatId, userId) 无法用于仅 userId 的查询，需要单独索引
    init {
        index("idx_chat_participants_user_id", false, userId)
    }
}

/** Per-user conversation state. It never contains message plaintext. */
object ChatUserSettings : Table("chat_user_settings") {
    val chatId = varchar("chat_id", 50) references Chats.id
    val userId = varchar("user_id", 50) references Users.id
    val pinnedAt = long("pinned_at").default(0)
    val notificationsMuted = bool("notifications_muted").default(false)
    val archived = bool("archived").default(false)
    val markedUnread = bool("marked_unread").default(false)
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(chatId, userId)

    init { index("idx_chat_user_settings_user_archive", false, userId, archived) }
}

/**
 * 1:1 私聊唯一对：跨进程/多实例创建时用 DB 唯一约束防重复，
 * 进程内 synchronized 无法覆盖多 JVM。
 * pairKey = sorted(userA,userB).join(":")
 */
object DirectChatPairs : Table("direct_chat_pairs") {
    val pairKey = varchar("pair_key", 120)
    val chatId = varchar("chat_id", 50) references Chats.id
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(pairKey)

    init {
        index("idx_direct_chat_pairs_chat", false, chatId)
    }
}

/**
 * 密聊唯一对：与 [DirectChatPairs] 分开，同一对人可同时有普通私聊和密聊。
 * pairKey = sorted(userA,userB).join(":")
 */
object SecretChatPairs : Table("secret_chat_pairs") {
    val pairKey = varchar("pair_key", 120)
    val chatId = varchar("chat_id", 50) references Chats.id
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(pairKey)

    init {
        index("idx_secret_chat_pairs_chat", false, chatId)
    }
}

object EncryptedAttachments : Table("encrypted_attachments") {
    val id = varchar("id", 100)
    val chatId = varchar("chat_id", 50) references Chats.id
    val uploaderId = varchar("uploader_id", 50) references Users.id
    val messageId = varchar("message_id", 100).nullable()
    val cipherSha256 = varchar("cipher_sha256", 64)
    val cipherSize = long("cipher_size")
    val uploadedBytes = long("uploaded_bytes").default(0)
    val status = varchar("status", 20).default("UPLOADED")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_attachments_chat", false, chatId)
        index("idx_attachments_uploader_status", false, uploaderId, status)
        index("idx_attachments_message", false, messageId)
        index("idx_attachments_expires", false, expiresAt)
    }
}

object Posts : Table("posts") {
    val id = varchar("id", 100)
    val authorId = varchar("author_id", 50) references Users.id
    val content = text("content")
    val imageUrls = text("image_urls").default("[]")
    val visibility = varchar("visibility", 20).default("PUBLIC")
    val status = varchar("status", 20).default("PUBLISHED")
    val createdAt = long("created_at")
    /** 编辑时间戳，null 表示未编辑 */
    val editedAt = long("edited_at").nullable()
    override val primaryKey = PrimaryKey(id)

    // getFeed 按 createdAt DESC 分页游标翻页 → 索引截断全表排序
    init {
        index("idx_posts_created_at", false, createdAt)
    }
}

/** 动态图片唯一占用：一张上传图片只能被一条动态使用（DB 级防重复，跨进程/重启仍生效）。 */
object PostImageClaims : Table("post_image_claims") {
    val filename = varchar("filename", 200)
    val postId = varchar("post_id", 100) references Posts.id
    val claimedAt = long("claimed_at")
    override val primaryKey = PrimaryKey(filename)

    init {
        index("idx_post_image_claims_post", false, postId)
    }
}

object PostLikes : Table("post_likes") {
    val postId = varchar("post_id", 100) references Posts.id
    val userId = varchar("user_id", 50) references Users.id
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(postId, userId)

    // toPostResponse 按 postId 聚合计数 → 索引让 count() 直接走覆盖索引
    init {
        index("idx_post_likes_post_id", false, postId)
    }
}

object PostComments : Table("post_comments") {
    val id = varchar("id", 100)
    val postId = varchar("post_id", 100) references Posts.id
    val authorId = varchar("author_id", 50) references Users.id
    val content = text("content")
    val createdAt = long("created_at")
    /** 1.76：评论回复——被回复评论 id（null=顶级评论）。 */
    val parentId = varchar("parent_id", 100).nullable().default(null)
    override val primaryKey = PrimaryKey(id)

    // getComments 按 postId + createdAt 排序分页 → 复合索引避免 filesort
    init {
        index("idx_post_comments_post_created", false, postId, createdAt)
    }
}

/** 评论点赞（1.52）：一行一赞，PK(commentId, userId) 防重复，可取消。 */
object CommentLikes : Table("comment_likes") {
    val commentId = varchar("comment_id", 100) references PostComments.id
    val userId = varchar("user_id", 50) references Users.id
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(commentId, userId)

    init {
        index("idx_comment_likes_comment", false, commentId)
    }
}

/** 好友申请：PENDING / ACCEPTED / REJECTED / CANCELLED */
object FriendRequests : Table("friend_requests") {
    val id = varchar("id", 80)
    val fromUserId = varchar("from_user_id", 50) references Users.id
    val toUserId = varchar("to_user_id", 50) references Users.id
    val message = varchar("message", 300).default("")
    val status = varchar("status", 20).default("PENDING")
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_friend_requests_to_status", false, toUserId, status)
        index("idx_friend_requests_from_status", false, fromUserId, status)
        index("idx_friend_requests_pair", false, fromUserId, toUserId)
    }
}

/** 已建立好友关系（无向边，存 min/max userId 序） */
object Friendships : Table("friendships") {
    val userLowId = varchar("user_low_id", 50) references Users.id
    val userHighId = varchar("user_high_id", 50) references Users.id
    val createdAt = long("created_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(userLowId, userHighId)

    init {
        index("idx_friendships_high", false, userHighId)
    }
}

/**
 * 9.3xx：群邀请同意流程——成员被拉入群前必须由本人接受。
 * 状态：PENDING（待接受）/ ACCEPTED / DECLINED / CANCELLED。
 * 唯一索引 (chat_id, user_id)：同一用户在同一群只能存在一条邀请记录（重复邀请幂等刷新）。
 */
object GroupInvitations : Table("group_invitations") {
    val id = varchar("id", 80)
    val chatId = varchar("chat_id", 50) references Chats.id
    val userId = varchar("user_id", 50) references Users.id
    val inviterId = varchar("inviter_id", 50) references Users.id
    val status = varchar("status", 20).default("PENDING")
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("idx_group_invitations_chat_user", chatId, userId)
        index("idx_group_invitations_user_status", false, userId, status)
        index("idx_group_invitations_chat_status", false, chatId, status)
    }
}


/** 会话文件夹云端同步（按用户） */
object ChatFolders : Table("chat_folders") {
    val userId = varchar("user_id", 50) references Users.id
    val folderId = varchar("folder_id", 80)
    val name = varchar("name", 80)
    val sortOrder = integer("sort_order").default(0)
    val chatIdsJson = text("chat_ids_json").default("[]")
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(userId, folderId)

    init {
        index("idx_chat_folders_user_sort", false, userId, sortOrder)
    }
}

/** 非敏感客户端外观/语言/列表/AI 写作风格/应用锁超时/防截屏偏好云同步（按用户；不含密钥与会话正文） */
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

object BlockedUsers : Table("blocked_users") {
    val blockerId = varchar("blocker_id", 50) references Users.id
    val blockedId = varchar("blocked_id", 50) references Users.id
    override val primaryKey = PrimaryKey(blockerId, blockedId)

    // 8.48 修复 M3：每次读消息/未读都执行 blocked_id = ?（双向拉黑过滤），
    // 主键 (blocker_id, blocked_id) 无法支撑该查询 → 补单列索引消除全表扫描
    init {
        index("idx_blocked_users_blocked_id", false, blockedId)
    }
}

object UserLocations : Table("user_locations") {
    val userId = varchar("user_id", 50) references Users.id
    val latitude = double("latitude")
    val longitude = double("longitude")
    val visible = bool("visible").default(true)
    val updatedAt = long("updated_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(userId)

    init {
        index("idx_user_locations_visible_expires", false, visible, expiresAt)
    }
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

object StarMessages : Table("star_messages") {
    val userId = varchar("user_id", 50) references Users.id
    val messageId = varchar("message_id", 100) references MessagingV2Messages.id
    val starredAt = long("starred_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(userId, messageId)
}

/** 会话级消息置顶（全员可见）；仅元数据，不存明文。 */
object PinnedMessages : Table("pinned_messages") {
    val chatId = varchar("chat_id", 50) references Chats.id
    val messageId = varchar("message_id", 100) references MessagingV2Messages.id
    val pinnedBy = varchar("pinned_by", 50) references Users.id
    val pinnedAt = long("pinned_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(chatId, messageId)

    init {
        index("idx_pinned_messages_chat_pinned_at", false, chatId, pinnedAt)
        index("idx_pinned_messages_message_id", false, messageId)
    }
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

object Reports : Table("reports") {
    val id = varchar("id", 100)
    val reporterId = varchar("reporter_id", 50) references Users.id
    val targetType = varchar("target_type", 20)
    val targetId = varchar("target_id", 100)
    val chatId = varchar("chat_id", 50).nullable()
    val messageId = varchar("message_id", 100).nullable()
    // 9.144：列宽与 ReportWorkflow 常量对齐（80/800）——此前 60/500 窄于仓库截断上限，
    // PG 严格 VARCHAR(n) 下 61-80/501-800 字符直接 22001 → 500（H2 宽松模式不暴露）
    val reason = varchar("reason", 80)
    val description = varchar("description", 800).nullable()
    val status = varchar("status", 20).default("OPEN")
    val reviewerId = varchar("reviewer_id", 50).nullable()
    val resolutionNote = varchar("resolution_note", 800).nullable()
    val actionTaken = varchar("action_taken", 40).nullable()
    val actionAt = long("action_at").nullable()
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val resolvedAt = long("resolved_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_reports_reporter_created", false, reporterId, createdAt)
        index("idx_reports_status_created", false, status, createdAt)
        index("idx_reports_target", false, targetType, targetId)
    }
}

/** 管理员操作审计：用户/内容/规则变更留痕 */
/** 9.154：moderation_audit_log.detail 列宽与入库截断上限（auditDetail 前缀 + 500 字备注超 500）。 */
const val MODERATION_AUDIT_DETAIL_MAX_CHARS = 800

object ModerationAuditLog : Table("moderation_audit_log") {
    val id = varchar("id", 100).clientDefault { java.util.UUID.randomUUID().toString() }
    val actorId = varchar("actor_id", 50).nullable()
    val userId = varchar("user_id", 50).nullable()
    val action = varchar("action", 40)
    // 9.154：detail 加宽 500→800——auditDetail 前缀 + 500 字备注超 500 字节（PG 22001 → 整事务回滚，
    // 封禁落空且 500）；入库侧统一按 MODERATION_AUDIT_DETAIL_MAX_CHARS 截断
    val detail = varchar("detail", 800).nullable()
    val createdAt = long("created_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(id)
}

object AiAuditLogs : Table("ai_audit_logs") {
    val id = varchar("id", 100)
    val userId = varchar("user_id", 50) references Users.id
    val chatId = varchar("chat_id", 50).nullable()
    val feature = varchar("feature", 40)
    val model = varchar("model", 80).nullable()
    val status = varchar("status", 30)
    val inputChars = integer("input_chars").default(0)
    val contextMessages = integer("context_messages").default(0)
    val durationMs = long("duration_ms").nullable()
    val error = varchar("error", 200).nullable()
    // 9.137：token 列正式进 Table 单例（此前靠运行时 ALTER TABLE + 裸 SQL 写入，
    // ALTER 失败会毒化 PG 事务并让 AI 主流程 500）。启动期 createMissingTablesAndColumns 自动补列。
    val inputTokens = long("input_tokens").nullable()
    val outputTokens = long("output_tokens").nullable()
    val createdAt = long("created_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_ai_audit_user_created", false, userId, createdAt)
    }
}


object ModerationRules : Table("moderation_rules") {
    val id = varchar("id", 80)
    val name = varchar("name", 100)
    val description = varchar("description", 500).nullable()
    val scope = varchar("scope", 20)
    val matchType = varchar("match_type", 20)
    val pattern = text("pattern")
    val action = varchar("action", 20)
    val windowMs = long("window_ms").default(0)
    val hitThreshold = integer("hit_threshold").default(0)
    val escalationAction = varchar("escalation_action", 20).nullable()
    val enabled = bool("enabled").default(true)
    val priority = integer("priority").default(100)
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_moderation_rules_enabled_priority", false, enabled, priority)
    }
}

object RiskEvents : Table("risk_events") {
    val id = varchar("id", 80)
    val userId = varchar("user_id", 50) references Users.id
    val sourceValue = varchar("source", 20)
    val ruleId = varchar("rule_id", 80).nullable()
    val action = varchar("action", 20)
    // 9.144：与 ModerationRuleRepository.MAX_MATCHED_LENGTH(280) 对齐，防 PG 22001
    val matched = varchar("matched", 280).nullable()
    val referenceId = varchar("reference_id", 100).nullable()
    val needsReview = bool("needs_review").default(false)
    val createdAt = long("created_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_risk_events_user_created", false, userId, createdAt)
        index("idx_risk_events_needs_review", false, needsReview, createdAt)
        index("idx_risk_events_rule_created", false, ruleId, createdAt)
    }
}

object GroupPolls : Table("group_polls") {
    val id = varchar("id", 64)
    val chatId = varchar("chat_id", 64).index()
    val creatorId = varchar("creator_id", 64)
    val question = varchar("question", 500)
    val optionsJson = text("options_json") // JSON array of strings
    val multi = bool("multi").default(false)
    val anonymous = bool("anonymous").default(false)
    val closed = bool("closed").default(false)
    val createdAt = long("created_at")
    val closesAt = long("closes_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object GroupPollVotes : Table("group_poll_votes") {
    val pollId = varchar("poll_id", 64)
    val userId = varchar("user_id", 64)
    val optionIndex = integer("option_index")
    val votedAt = long("voted_at")
    override val primaryKey = PrimaryKey(pollId, userId, optionIndex)
}

object BotApps : Table("bot_apps") {
    val id = varchar("id", 64)
    val ownerUserId = varchar("owner_user_id", 64).index()
    val name = varchar("name", 120)
    val username = varchar("username", 64).uniqueIndex()
    val description = text("description").nullable()
    val tokenHash = varchar("token_hash", 128)
    val tokenPrefix = varchar("token_prefix", 16)
    val webhookUrl = varchar("webhook_url", 500).nullable()
    val commandsJson = text("commands_json").nullable()
    val enabled = bool("enabled").default(true)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object BotCommandLogs : Table("bot_command_logs") {
    val id = varchar("id", 64)
    val botId = varchar("bot_id", 64).index()
    val chatId = varchar("chat_id", 64).nullable()
    val userId = varchar("user_id", 64).nullable()
    val command = varchar("command", 120)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** B12：webhook 投递 outbox/死信（重启可重放 PENDING，失败入 DEAD 供审计/重放）。 */
object BotWebhookOutbox : Table("bot_webhook_outbox") {
    val id = varchar("id", 100)
    val botId = varchar("bot_id", 64).index()
    val url = varchar("url", 500)
    val tokenHash = varchar("token_hash", 128)
    val body = text("body")
    val ts = long("ts")
    val attempts = integer("attempts").default(0)
    val status = varchar("status", 20).default("PENDING").index()
    val leaseOwner = varchar("lease_owner", 100).nullable()
    val leaseUntil = long("lease_until").default(0L).index()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object SystemSettings : Table("system_settings") {
    val key = varchar("key", 64)
    val value = text("value")
    val updatedAt = long("updated_at")
    val updatedBy = varchar("updated_by", 50).nullable()
    override val primaryKey = PrimaryKey(key)
}

object BotUpdateInbox : Table("bot_update_inbox") {
    val id = long("id").autoIncrement()
    val botId = varchar("bot_id", 64).index()
    val updateJson = text("update_json")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}