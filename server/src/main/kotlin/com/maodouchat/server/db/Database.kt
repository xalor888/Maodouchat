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

/** 当前连接的数据库是否为 H2（测试常用）。H2 2.x 不支持 Exposed 单键 upsert 生成的 MERGE ... USING (VALUES)。 */
internal fun isH2Db(): Boolean =
    TransactionManager.current().db.vendor.contains("h2", ignoreCase = true)

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

object Messages : Table("messages") {
    val id = varchar("id", 100)
    val chatId = varchar("chat_id", 50) references Chats.id
    val senderId = varchar("sender_id", 50) references Users.id
    val content = text("content")
    val type = varchar("type", 20).default("TEXT")
    val timestamp = long("timestamp")
    val status = varchar("status", 20).default("SENT")
    /** 编辑时间戳，null 表示未编辑 */
    val editedAt = long("edited_at").nullable()
    /** 阅后即焚截止时间；null/0=不销毁；首次对方已读时写入 */
    val expiresAt = long("expires_at").nullable()
    /** When true, fan-out/push/webhooks redact sender metadata (sealed-sender style). */
    val sealedSender = bool("sealed_sender").default(false)
    override val primaryKey = PrimaryKey(id)

    // 缺少索引会让 getMessages 的 WHERE chatId ORDER BY timestamp DESC 全表扫描
    // (chat_id, timestamp, id) 支撑 getMessagesSince 的 (ts,id) 游标分页
    init {
        index("idx_messages_chat_ts", false, chatId, timestamp)
        index("idx_messages_chat_ts_id", false, chatId, timestamp, id)
        index("idx_messages_expires_at", false, expiresAt)
    }
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

/**
 * 消息变更日志：DELETE/REVOKE/EDIT 写入后供多设备增量同步。
 * 离线设备无法只靠 WS 收到变更，必须能按 (createdAt, id) 游标回放。
 */
object MessageMutations : Table("message_mutations") {
    val id = varchar("id", 100)
    val chatId = varchar("chat_id", 50) references Chats.id
    val messageId = varchar("message_id", 100)
    /** DELETE / REVOKE / EDIT */
    val action = varchar("action", 20)
    val actorId = varchar("actor_id", 50)
    /** EDIT 时的新密文；REVOKE 时为墓碑文案；DELETE 为空 */
    val content = text("content").nullable()
    val editedAt = long("edited_at").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_message_mutations_chat_created", false, chatId, createdAt, id)
        index("idx_message_mutations_message", false, messageId)
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
    val messageId = varchar("message_id", 100) references Messages.id
    val starredAt = long("starred_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(userId, messageId)
}

/** 会话级消息置顶（全员可见）；仅元数据，不存明文。 */
object PinnedMessages : Table("pinned_messages") {
    val chatId = varchar("chat_id", 50) references Chats.id
    val messageId = varchar("message_id", 100) references Messages.id
    val pinnedBy = varchar("pinned_by", 50) references Users.id
    val pinnedAt = long("pinned_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(chatId, messageId)

    init {
        index("idx_pinned_messages_chat_pinned_at", false, chatId, pinnedAt)
        index("idx_pinned_messages_message_id", false, messageId)
    }
}

object ReadReceipts : Table("read_receipts") {
    val messageId = varchar("message_id", 100) references Messages.id
    val userId = varchar("user_id", 50) references Users.id
    val readAt = long("read_at")
    override val primaryKey = PrimaryKey(messageId, userId)

    init {
        index("idx_read_receipts_message_id", false, messageId)
        index("idx_read_receipts_user_id", false, userId)
    }
}

object MessageReactions : Table("message_reactions") {
    val messageId = varchar("message_id", 100) references Messages.id
    val userId = varchar("user_id", 50) references Users.id
    val emoji = varchar("emoji", 16)
    val reactedAt = long("reacted_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(messageId, userId)

    init {
        index("idx_message_reactions_message_id", false, messageId)
    }
}

object SenderKeyDistributions : Table("sender_key_distributions") {
    val id = varchar("id", 100)
    val chatId = varchar("chat_id", 50) references Chats.id
    val epoch = long("epoch")
    val senderId = varchar("sender_id", 50) references Users.id
    val recipientUserId = varchar("recipient_user_id", 50) references Users.id
    val recipientDeviceId = integer("recipient_device_id")
    val messageId = varchar("message_id", 100).nullable()
    val status = varchar("status", 20).default("SENT")
    val error = varchar("error", 200).nullable()
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_sender_key_dist_chat_epoch", false, chatId, epoch)
        index("idx_sender_key_dist_sender_epoch", false, chatId, senderId, epoch)
        index("idx_sender_key_dist_recipient", false, recipientUserId, recipientDeviceId)
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
    // 9.144：列宽与 ReportRepository 常量对齐（80/800）——此前 60/500 窄于仓库截断上限，
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

fun initDatabase() {
    org.jetbrains.exposed.sql.transactions.transaction {

        SchemaUtils.createMissingTablesAndColumns(
            Users, Chats, ChatParticipants, ChatUserSettings, GroupAuditLogs, Messages, MessageMutations,
            EncryptedAttachments, SignalKeys, SignalDevices, SignalingMessages, Posts, PostImageClaims, PostLikes, PostComments, CommentLikes,
            BlockedUsers, UserLocations, AuthSessions, RefreshTokens, RevokedAccessTokens, StarMessages, PinnedMessages,
            ReadReceipts, MessageReactions, SenderKeyDistributions, NotificationPreferences,
            PushTokens, GroupPolls, GroupPollVotes, BotApps, BotCommandLogs, BotUpdateInbox, Reports, ModerationAuditLog, AiAuditLogs, ModerationRules,
            RiskEvents, DirectChatPairs, SecretChatPairs, FriendRequests, Friendships, ChatFolders, ClientPrefs, SystemSettings,
            // 9.3xx：群邀请同意流程（成员入群前须本人接受）
            GroupInvitations,
            // 群玩法 B3：群签到 / 群接龙 / 群 PK（表定义见 PollTables.kt）
            GroupCheckins, GroupChains, GroupChainEntries, GroupPkRounds, GroupPkVotes,
            // B6 运维增强：用户标签先于公告建表（公告 target_tag_id 外键引用 user_tags.id）
            UserTags, UserTagAssignments, SystemAnnouncements, AnnouncementAcks, AuditExportRecords,
            RateLimitStatsSnapshots, DeviceEventSequences, DeviceEventConsistencyLog
        )
        widenClientPrefsWritingStyleColumn()
        widenFriendRequestMessageColumn()
        // 9.144：既有库加宽（新增实例由 Table 定义直接建宽列）
        widenReportsColumns()
        widenRiskEventsMatchedColumn()
        widenModerationAuditDetailColumn()
        // 确保 init {} 中的索引被创建（createMissingTablesAndColumns 可能不会自动建）
        ensureIndexes()
        dropRetiredCloudAiTables()
        // 9.4xx：PostgreSQL 全文/模糊搜索索引（pg_trgm；H2 与受限环境自动跳过）
        ensureSearchIndexes()
        migrateAuthSessionState()
        backfillSignalKeyDeviceIds()
        backfillSignalDeviceConfirmation()
        backfillMemberRoles()
        backfillChatTypes()
        backfillModeratorEmails()
        backfillModerationRules()
    }
}

/**
 * 存量 chats 行补全 chat_type：群聊推导为 GROUP，私聊推导为 DIRECT。
 * 新列由 createMissingTablesAndColumns 自动补列（默认 DIRECT），随后此处修正历史群聊。
 */

private fun dropRetiredCloudAiTables() {
    try {
        TransactionManager.current().exec("DROP TABLE IF EXISTS ai_summary_sync_envelopes")
        TransactionManager.current().exec("DROP TABLE IF EXISTS ai_preferences")
    } catch (_: Exception) {
        // H2/Postgres naming differences; leftover tables are unused.
    }
}

private fun backfillChatTypes() {
    try {
        TransactionManager.current().exec(
            "UPDATE chats SET chat_type = 'GROUP' WHERE is_group = true AND (chat_type IS NULL OR chat_type = '' OR chat_type = 'DIRECT')"
        )
    } catch (e: Exception) {
        // Column naming differs across H2/Postgres migrations; rethrow without leaking schema dumps.
        throw e
    }
}

private fun migrateAuthSessionState() {
    val now = System.currentTimeMillis()
    // Pre-session refresh tokens cannot satisfy the mandatory auth_session_id contract.
    TransactionManager.current().exec(
        "UPDATE refresh_tokens SET revoked_at = $now " +
            "WHERE revoked_at IS NULL AND (session_id IS NULL OR session_id = '')"
    )
    TransactionManager.current().exec(
        "UPDATE refresh_tokens SET revoked_at = $now WHERE revoked_at IS NULL AND NOT EXISTS (" +
            "SELECT 1 FROM auth_sessions s WHERE s.id = refresh_tokens.session_id " +
            "AND s.user_id = refresh_tokens.user_id AND s.revoked_at IS NULL)"
    )
    TransactionManager.current().exec(
        "DELETE FROM push_tokens WHERE auth_session_id IS NULL OR auth_session_id = '' OR NOT EXISTS (" +
            "SELECT 1 FROM auth_sessions s WHERE s.id = push_tokens.auth_session_id " +
            "AND s.user_id = push_tokens.user_id AND s.revoked_at IS NULL)"
    )
}

private fun widenClientPrefsWritingStyleColumn() {
    // 按「实际连接的数据库」判断（而非 ServerConfig.databaseDriver——测试直接 connect 时该值可能仍是默认 H2）
    val isPostgres = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .db.vendor.contains("postgres", ignoreCase = true)
    val sql = if (isPostgres) {
        "ALTER TABLE client_prefs ALTER COLUMN writing_style_custom TYPE VARCHAR(320)"
    } else {
        "ALTER TABLE client_prefs ALTER COLUMN writing_style_custom VARCHAR(320)"
    }
    TransactionManager.current().exec(sql)
}

private fun widenFriendRequestMessageColumn() {
    val isPostgres = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .db.vendor.contains("postgres", ignoreCase = true)
    val sql = if (isPostgres) {
        "ALTER TABLE friend_requests ALTER COLUMN message TYPE VARCHAR(300)"
    } else {
        "ALTER TABLE friend_requests ALTER COLUMN message VARCHAR(300)"
    }
    TransactionManager.current().exec(sql)
}

/** 9.144：既有库加宽 reports 文本列（createMissingTablesAndColumns 只补列不扩列）。 */
private fun widenReportsColumns() {
    val isPostgres = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .db.vendor.contains("postgres", ignoreCase = true)
    val alters = if (isPostgres) {
        listOf(
            "ALTER TABLE reports ALTER COLUMN reason TYPE VARCHAR(80)",
            "ALTER TABLE reports ALTER COLUMN description TYPE VARCHAR(800)",
            "ALTER TABLE reports ALTER COLUMN resolution_note TYPE VARCHAR(800)",
        )
    } else {
        listOf(
            "ALTER TABLE reports ALTER COLUMN reason VARCHAR(80)",
            "ALTER TABLE reports ALTER COLUMN description VARCHAR(800)",
            "ALTER TABLE reports ALTER COLUMN resolution_note VARCHAR(800)",
        )
    }
    alters.forEach { TransactionManager.current().exec(it) }
}

/** 9.144：既有库加宽 risk_events.matched（同 reports）。 */
private fun widenRiskEventsMatchedColumn() {
    val isPostgres = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .db.vendor.contains("postgres", ignoreCase = true)
    val sql = if (isPostgres) {
        "ALTER TABLE risk_events ALTER COLUMN \"matched\" TYPE VARCHAR(280)"
    } else {
        "ALTER TABLE risk_events ALTER COLUMN \"matched\" VARCHAR(280)"
    }
    TransactionManager.current().exec(sql)
}

/** 9.154：既有库加宽 moderation_audit_log.detail 500→800（同 reports 口径，createMissingTablesAndColumns 只补列不扩列）。 */
private fun widenModerationAuditDetailColumn() {
    val isPostgres = org.jetbrains.exposed.sql.transactions.TransactionManager.current()
        .db.vendor.contains("postgres", ignoreCase = true)
    val sql = if (isPostgres) {
        "ALTER TABLE moderation_audit_log ALTER COLUMN detail TYPE VARCHAR(800)"
    } else {
        "ALTER TABLE moderation_audit_log ALTER COLUMN detail VARCHAR(800)"
    }
    TransactionManager.current().exec(sql)
}

private fun ensureIndexes() {
    val indexes = listOf(
        "CREATE INDEX IF NOT EXISTS idx_messages_chat_ts ON messages(chat_id, timestamp)",
        "CREATE INDEX IF NOT EXISTS idx_messages_chat_ts_id ON messages(chat_id, timestamp, id)",
        "CREATE INDEX IF NOT EXISTS idx_message_mutations_chat_created ON message_mutations(chat_id, created_at, id)",
        "CREATE INDEX IF NOT EXISTS idx_direct_chat_pairs_chat ON direct_chat_pairs(chat_id)",
        "CREATE INDEX IF NOT EXISTS idx_message_mutations_message ON message_mutations(message_id)",
        "CREATE INDEX IF NOT EXISTS idx_attachments_chat ON encrypted_attachments(chat_id)",
        "CREATE INDEX IF NOT EXISTS idx_attachments_uploader_status ON encrypted_attachments(uploader_id, status)",
        "CREATE INDEX IF NOT EXISTS idx_attachments_message ON encrypted_attachments(message_id)",
        "CREATE INDEX IF NOT EXISTS idx_attachments_expires ON encrypted_attachments(expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_posts_created_at ON posts(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_posts_author_created ON posts(author_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_post_likes_post_id ON post_likes(post_id)",
        // 9.155：点赞者列表按 (post_id, created_at DESC, user_id DESC) 键集分页——复合覆盖索引
        "CREATE INDEX IF NOT EXISTS idx_post_likes_post_created_user ON post_likes(post_id, created_at, user_id)",
        "CREATE INDEX IF NOT EXISTS idx_post_comments_post_created ON post_comments(post_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_friend_requests_to_status ON friend_requests(to_user_id, status)",
        "CREATE INDEX IF NOT EXISTS idx_friend_requests_from_status ON friend_requests(from_user_id, status)",
        "CREATE INDEX IF NOT EXISTS idx_friend_requests_pair ON friend_requests(from_user_id, to_user_id)",
        "CREATE INDEX IF NOT EXISTS idx_friendships_high ON friendships(user_high_id)",
        "CREATE INDEX IF NOT EXISTS idx_chat_folders_user_sort ON chat_folders(user_id, sort_order)",
        "CREATE INDEX IF NOT EXISTS idx_group_polls_chat_created ON group_polls(chat_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_group_poll_votes_user ON group_poll_votes(user_id, voted_at)",
        "CREATE INDEX IF NOT EXISTS idx_bot_apps_owner_created ON bot_apps(owner_user_id, created_at)",
        "CREATE UNIQUE INDEX IF NOT EXISTS uidx_bot_apps_token_hash ON bot_apps(token_hash)",
        "CREATE INDEX IF NOT EXISTS idx_bot_command_logs_bot_created ON bot_command_logs(bot_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_bot_update_inbox_bot_id ON bot_update_inbox(bot_id, id)",
        "CREATE INDEX IF NOT EXISTS idx_signal_keys_user_device_type ON signal_keys(user_id, device_id, key_type)",
        "CREATE INDEX IF NOT EXISTS idx_signal_devices_user ON signal_devices(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_signal_devices_user_status ON signal_devices(user_id, status)",
        "CREATE INDEX IF NOT EXISTS idx_signaling_to_user_ts ON signaling_messages(to_user_id, timestamp)",
        // Bug #26: chat_participants 按 userId 查询的索引
        "CREATE INDEX IF NOT EXISTS idx_chat_participants_user_id ON chat_participants(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_user_locations_visible_expires ON user_locations(visible, expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_device ON auth_sessions(user_id, signal_device_id)",
        "CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_refresh_tokens_session_id ON refresh_tokens(session_id)",
        "CREATE INDEX IF NOT EXISTS idx_revoked_access_tokens_user_id ON revoked_access_tokens(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_revoked_access_tokens_expires_at ON revoked_access_tokens(expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_read_receipts_message_id ON read_receipts(message_id)",
        "CREATE INDEX IF NOT EXISTS idx_read_receipts_user_id ON read_receipts(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_message_reactions_message_id ON message_reactions(message_id)",
        "CREATE INDEX IF NOT EXISTS idx_pinned_messages_chat_pinned_at ON pinned_messages(chat_id, pinned_at)",
        "CREATE INDEX IF NOT EXISTS idx_pinned_messages_message_id ON pinned_messages(message_id)",
        "CREATE INDEX IF NOT EXISTS idx_sender_key_dist_chat_epoch ON sender_key_distributions(chat_id, epoch)",
        "CREATE INDEX IF NOT EXISTS idx_sender_key_dist_recipient ON sender_key_distributions(recipient_user_id, recipient_device_id)",
        "CREATE INDEX IF NOT EXISTS idx_push_tokens_user_id ON push_tokens(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_push_tokens_auth_session ON push_tokens(auth_session_id)",
        "CREATE INDEX IF NOT EXISTS idx_ai_audit_user_created ON ai_audit_logs(user_id, created_at)",

        "CREATE INDEX IF NOT EXISTS idx_reports_reporter_created ON reports(reporter_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_reports_status_created ON reports(status, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_reports_target ON reports(target_type, target_id)",
        "CREATE INDEX IF NOT EXISTS idx_moderation_rules_enabled_priority ON moderation_rules(enabled, priority)",
        "CREATE INDEX IF NOT EXISTS idx_risk_events_user_created ON risk_events(user_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_risk_events_needs_review ON risk_events(needs_review, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_risk_events_rule_created ON risk_events(rule_id, created_at)",
        // B6 运维增强：公告 / 用户标签 / 审计导出 / 限流统计 / 设备一致性索引
        "CREATE INDEX IF NOT EXISTS idx_announcements_status_window ON system_announcements(status, starts_at, expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_announcements_created_at ON system_announcements(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_user_tag_assignments_user ON user_tag_assignments(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_audit_export_actor_created ON audit_export_records(actor_id, requested_at)",
        "CREATE UNIQUE INDEX IF NOT EXISTS uidx_rate_limit_stats_bucket ON rate_limit_stats_snapshots(bucket_start_ms)",
        "CREATE INDEX IF NOT EXISTS idx_rate_limit_stats_bucket_start ON rate_limit_stats_snapshots(bucket_start_ms)",
        "CREATE INDEX IF NOT EXISTS idx_device_event_log_user_ts ON device_event_consistency_log(user_id, last_seen_at)",
        "CREATE INDEX IF NOT EXISTS idx_device_event_log_status ON device_event_consistency_log(status, last_seen_at)",
        // 8.48 修复：blocked_users.blocked_id 单列索引——此前仅在 Table.init{} 声明，
        // 但 ensureIndexes 是权威创建路径（createMissingTablesAndColumns 对已存在表不建索引），
        // 未加入列表则对已部署库永不生效（双向拉黑过滤每次读消息全表扫）
        "CREATE INDEX IF NOT EXISTS idx_blocked_users_blocked_id ON blocked_users(blocked_id)",
        // 8.30 调优：保留期清理/周期任务的时间列独立索引（8.29 性能扫描 F1/F2/F3/F5），
        // 消除全表扫描 DELETE/UPDATE：
        // - deleteExpired（15 分钟循环）按 refresh_tokens.expires_at 两次全扫
        // - purgeStaleInTx（每次 store/poll）按 signaling_messages.timestamp 全扫
        // - purgeOldDerivedRows 按 messages.timestamp 驱动 3 张表
        // - 全部周期清理按各自时间列
        "CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at ON refresh_tokens(expires_at)",
        // timestamp 是 SQL 保留字（Exposed 存小写带引号）；signal_keys 的 key_type 列
        // 同样为小写带引号存储（历史建表方式），两者原生 DDL 必须加引号，其余列大写裸写。
        "CREATE INDEX IF NOT EXISTS idx_signaling_ts ON signaling_messages(\"timestamp\")",
        "CREATE INDEX IF NOT EXISTS idx_messages_timestamp ON messages(\"timestamp\")",
        "CREATE INDEX IF NOT EXISTS idx_message_mutations_created_at ON message_mutations(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_group_audit_created_at ON group_audit_logs(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_bot_update_inbox_created_at ON bot_update_inbox(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_bot_command_logs_created_at ON bot_command_logs(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_ai_audit_created_at ON ai_audit_logs(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_sender_key_dist_created_at ON sender_key_distributions(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_risk_events_created_at ON risk_events(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_signal_keys_type_created ON signal_keys(\"key_type\", created_at)",
        "CREATE INDEX IF NOT EXISTS idx_group_checkins_checked_at ON group_checkins(checked_at)",
        "CREATE INDEX IF NOT EXISTS idx_group_chains_created_at ON group_chains(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_group_pk_rounds_created_at ON group_pk_rounds(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_user_locations_expires_at ON user_locations(expires_at)",
        // 8.48 修复：以下旧表索引仅声明在 Table.init{}——init 对「已存在表」不建索引
        //（ensureIndexes 才是权威创建路径），未加入列表则对已部署库永不生效：
        "CREATE INDEX IF NOT EXISTS idx_messages_expires_at ON messages(expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_chat_user_settings_user_archive ON chat_user_settings(user_id, archived)",
        "CREATE INDEX IF NOT EXISTS idx_group_audit_chat_created ON group_audit_logs(chat_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_sender_key_dist_sender_epoch ON sender_key_distributions(chat_id, sender_id, epoch)",
        "CREATE INDEX IF NOT EXISTS idx_signaling_call ON signaling_messages(call_id, from_user_id, to_user_id)",
        "CREATE INDEX IF NOT EXISTS idx_signaling_group_call ON signaling_messages(group_id, call_id)",
        // B3 群玩法表索引：虽为新表（建表时 init 会建），补入列表保证 Table.init 与 ensureIndexes
        // 完全对齐（IF NOT EXISTS 无害，防任何建表路径差异）
        "CREATE INDEX IF NOT EXISTS idx_group_checkins_chat_date ON group_checkins(chat_id, checkin_date)",
        "CREATE INDEX IF NOT EXISTS idx_group_checkins_user ON group_checkins(chat_id, user_id)",
        "CREATE INDEX IF NOT EXISTS idx_group_chains_chat_created ON group_chains(chat_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_group_chain_entries_chain_seq ON group_chain_entries(chain_id, sequence)",
        "CREATE INDEX IF NOT EXISTS idx_group_chain_entries_chain_user ON group_chain_entries(chain_id, user_id)",
        "CREATE INDEX IF NOT EXISTS idx_group_pk_chat_created ON group_pk_rounds(chat_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_group_pk_votes_pk ON group_pk_votes(pk_id)",
        // 8.48 修复 L15：管理仪表盘趋势/活跃用户范围扫描（Users.last_seen、Reports.created_at）
        "CREATE INDEX IF NOT EXISTS idx_users_last_seen ON users(last_seen)",
        "CREATE INDEX IF NOT EXISTS idx_reports_created_at ON reports(created_at)",
    )
    for (sql in indexes) {
        TransactionManager.current().exec(sql)
    }
    // 部分唯一索引（8.27 调优）：PostgreSQL 支持 WHERE 子句并在部分行上强制唯一，
    // 兜底并发绕过应用层去重；H2 不支持该语法，测试环境跳过（应用层冲突捕获仍有）。
    val isPostgres = TransactionManager.current().db.dialect is org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
    if (isPostgres) {
        val partialUniqueIndexes = listOf(
            // 同一举报人对同一目标的 OPEN 举报唯一 —— 防并发提交绕过 24h 去重制造重复审核负载
            "CREATE UNIQUE INDEX IF NOT EXISTS uidx_reports_open_dedup ON reports(reporter_id, target_type, target_id) WHERE status = 'OPEN'",
            // 好友 PENDING 申请唯一：正常路径有用户对行锁串行化，这里兜底未来不经锁的插入路径
            "CREATE UNIQUE INDEX IF NOT EXISTS uidx_friend_requests_pending ON friend_requests(from_user_id, to_user_id) WHERE status = 'PENDING'",
        )
        for (sql in partialUniqueIndexes) {
            TransactionManager.current().exec(sql)
        }
    }
}

/** 9.4xx：PostgreSQL 模糊搜索索引。pg_trgm GIN 加速 %pattern% 形式的 LIKE（普通 B-tree 无法利用前导通配符）。 */
private fun ensureSearchIndexes() {
    if (isH2Db()) return
    try {
        TransactionManager.current().exec("CREATE EXTENSION IF NOT EXISTS pg_trgm")
        TransactionManager.current().exec(
            "CREATE INDEX IF NOT EXISTS idx_users_name_trgm ON users USING GIN (lower(name) gin_trgm_ops)"
        )
        TransactionManager.current().exec(
            "CREATE INDEX IF NOT EXISTS idx_users_username_trgm ON users USING GIN (lower(username) gin_trgm_ops)"
        )
        TransactionManager.current().exec(
            "CREATE INDEX IF NOT EXISTS idx_users_email_trgm ON users USING GIN (lower(email) gin_trgm_ops)"
        )
    } catch (e: Exception) {
        // 扩展不可用（受限托管 PG / 权限不足）时降级为原 LIKE 全扫描，不影响功能
        org.slf4j.LoggerFactory.getLogger("Database")
            .warn("pg_trgm search indexes unavailable; user search falls back to full scan: {}", e.message)
    }
}

private fun backfillSignalKeyDeviceIds() {
    TransactionManager.current().exec("UPDATE signal_keys SET device_id = 1 WHERE device_id IS NULL")
}

private fun backfillSignalDeviceConfirmation() {
    val now = System.currentTimeMillis()
    TransactionManager.current().exec("UPDATE signal_devices SET status = 'CONFIRMED' WHERE status IS NULL OR status = ''")
    TransactionManager.current().exec("UPDATE signal_devices SET confirmed_at = $now WHERE status = 'CONFIRMED' AND confirmed_at IS NULL")
}

private fun backfillMemberRoles() {
    try {
        TransactionManager.current().exec("UPDATE chat_participants SET \"role\" = 'MEMBER' WHERE \"role\" IS NULL OR \"role\" = ''")
    } catch (e: Exception) {
        // Column naming differs across H2/Postgres migrations; rethrow without leaking schema dumps.
        throw e
    }
}

private fun backfillModeratorEmails() {
    // 8.46：改为 Exposed 参数化 DSL，替代原生 SQL 字符串拼接（配置来源也统一走参数绑定，
    // 彻底消除注入面；语义与旧 LOWER(email) IN (...) 完全一致）。
    val emails = com.maodouchat.server.config.ServerConfig.moderatorEmails.map { it.lowercase() }
    if (emails.isEmpty()) return
    Users.update({ Users.email.lowerCase() inList emails }) {
        it[Users.isModerator] = true
    }
}

private fun backfillModerationRules() {
    val now = System.currentTimeMillis()
    DEFAULT_MODERATION_RULES.forEach { seed ->
        if (ModerationRules.selectAll().where { ModerationRules.id eq seed.id }.firstOrNull() != null) {
            // 修复旧数据：rule_spam_keywords / rule_short_link 早期以 KEYWORD/URL 类型存了
            // 正则 pattern（字面量匹配永不命中，默认内容安全防线形同虚设），统一纠正为 REGEX。
            if (seed.matchType == "REGEX") {
                val existingType = ModerationRules.select(ModerationRules.matchType)
                    .where { ModerationRules.id eq seed.id }.firstOrNull()?.get(ModerationRules.matchType)
                if (existingType != "REGEX") {
                    ModerationRules.update({ ModerationRules.id eq seed.id }) { it[ModerationRules.matchType] = "REGEX" }
                }
            }
            return@forEach
        }
        ModerationRules.insert {
            it[ModerationRules.id] = seed.id
            it[ModerationRules.name] = seed.name
            it[ModerationRules.description] = seed.description
            it[ModerationRules.scope] = seed.scope
            it[ModerationRules.matchType] = seed.matchType
            it[ModerationRules.pattern] = seed.pattern
            it[ModerationRules.action] = seed.action
            it[ModerationRules.windowMs] = seed.windowMs
            it[ModerationRules.hitThreshold] = seed.hitThreshold
            it[ModerationRules.escalationAction] = seed.escalationAction
            it[ModerationRules.enabled] = true
            it[ModerationRules.priority] = seed.priority
            it[ModerationRules.createdAt] = now
            it[ModerationRules.updatedAt] = now
        }
    }
}

private data class SeedModerationRule(
    val id: String,
    val name: String,
    val description: String,
    val scope: String,
    val matchType: String,
    val pattern: String,
    val action: String,
    val windowMs: Long,
    val hitThreshold: Int,
    val escalationAction: String?,
    val priority: Int
)

private val DEFAULT_MODERATION_RULES = listOf(
    SeedModerationRule(
        id = "rule_spam_keywords",
        name = "常见营销引流关键词",
        description = "命中常见营销、博彩或引流词时进入人工复核",
        scope = "ALL",
        matchType = "REGEX",
        pattern = "兼职\\s*日结|免费\\s*约|加我\\s*v?x|代刷|网赚|博彩|网赌",
        action = "WARN_MOD",
        windowMs = 24L * 60L * 60L * 1000L,
        hitThreshold = 3,
        escalationAction = "AUTO_RATE_LIMIT",
        priority = 50
    ),
    SeedModerationRule(
        id = "rule_short_link",
        name = "可疑短链",
        description = "动态或评论中出现常见短链时进入人工复核",
        scope = "ALL",
        matchType = "REGEX",
        pattern = "(?:t\\.cn|bit\\.ly|tinyurl\\.com|goo\\.gl|is\\.gd|ow\\.ly|buff\\.ly|adf\\.ly)",
        action = "WARN_MOD",
        windowMs = 24L * 60L * 60L * 1000L,
        hitThreshold = 2,
        escalationAction = "AUTO_HOLD",
        priority = 80
    ),
    SeedModerationRule(
        id = "rule_post_flood",
        name = "高频发动态",
        description = "一分钟内发布超过五条动态会进入限流",
        scope = "POST",
        matchType = "FREQUENCY",
        pattern = "*",
        action = "AUTO_RATE_LIMIT",
        windowMs = 60_000L,
        hitThreshold = 5,
        escalationAction = "AUTO_HOLD",
        priority = 20
    ),
    SeedModerationRule(
        id = "rule_comment_flood",
        name = "高频发评论",
        description = "一分钟内发布超过十条评论会进入限流",
        scope = "COMMENT",
        matchType = "FREQUENCY",
        pattern = "*",
        action = "AUTO_RATE_LIMIT",
        windowMs = 60_000L,
        hitThreshold = 10,
        escalationAction = "AUTO_HOLD",
        priority = 20
    )
)
