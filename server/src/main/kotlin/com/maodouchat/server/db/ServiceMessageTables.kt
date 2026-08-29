package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table

/** Server-authored bot content. Human E2EE messages never enter this table. */
object ServiceMessages : Table("service_messages") {
    val id = varchar("id", 100)
    val chatId = varchar("chat_id", 50) references Chats.id
    val senderId = varchar("sender_id", 50) references Users.id
    val content = text("content")
    val type = varchar("type", 20)
    val timestamp = long("timestamp")
    val editedAt = long("edited_at").nullable()
    val deletedAt = long("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_service_messages_chat_time", false, chatId, timestamp)
        index("idx_service_messages_sender_time", false, senderId, timestamp)
    }
}

/** Plaintext bot reactions only; user reactions remain encrypted v2 events. */
object ServiceMessageReactions : Table("service_message_reactions") {
    val messageId = varchar("message_id", 100) references MessagingV2Messages.id
    val botUserId = varchar("bot_user_id", 50) references Users.id
    val emoji = varchar("emoji", 16)
    val reactedAt = long("reacted_at")
    override val primaryKey = PrimaryKey(messageId, botUserId)

    init {
        index("idx_service_reactions_message", false, messageId)
    }
}
