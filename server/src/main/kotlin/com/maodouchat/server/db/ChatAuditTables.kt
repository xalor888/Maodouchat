package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table

// 会话审计与邀请表。

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

