package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table

// 会话功能表（星标 / 置顶 / 文件夹）。

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

