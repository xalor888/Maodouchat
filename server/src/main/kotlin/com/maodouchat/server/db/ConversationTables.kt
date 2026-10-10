package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table

// 会话、成员、邀请与审计表。

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
