package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table

// 加密附件表。

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
