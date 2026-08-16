package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("chatId"),
        Index("timestamp"),
        Index("type"),
        Index(value = ["chatId", "type", "timestamp"]),
        // B7: 仅追加索引，与 MIGRATION_27_28 保持一致，保证新建库与迁移库 schema 等价
        Index(value = ["chatId", "timestamp"]),
        Index(value = ["status", "senderId", "timestamp"]),
        Index("expiresAt"),
        Index(value = ["type", "timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val type: String = MessageType.TEXT.name,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = MessageStatus.SENT.name,
    val editedAt: Long? = null,
    val starred: Boolean = false,
    val reactionsJson: String = "[]",
    val expiresAt: Long? = null,
    // 8.49 修复：sealed-sender 标志持久化——此前仅存于瞬态域字段，DB round-trip 后恒为
    // false，断线重连从 outbox 重发的密封消息会以非密封方式发送（隐私降级）
    val sealedSender: Boolean = false
)

fun MessageEntity.toDomain(): Message = Message(
    id = id,
    chatId = chatId,
    senderId = senderId,
    content = content,
    type = MessageType.fromWire(type),
    timestamp = timestamp,
    status = MessageStatus.fromWire(status),
    editedAt = editedAt,
    starred = starred,
    reactions = decodeReactions(reactionsJson),
    expiresAt = expiresAt?.takeIf { it > 0L },
    sealedSender = sealedSender
)

fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    chatId = chatId,
    senderId = senderId,
    content = content,
    type = type.name,
    timestamp = timestamp,
    status = status.name,
    editedAt = editedAt,
    starred = starred,
    reactionsJson = encodeReactions(reactions),
    expiresAt = expiresAt?.takeIf { it > 0L },
    sealedSender = sealedSender
)

private val messageEntityJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun encodeReactions(reactions: List<MessageReaction>): String =
    runCatching { messageEntityJson.encodeToString(ListSerializer(MessageReaction.serializer()), reactions) }.getOrDefault("[]")

private fun decodeReactions(text: String): List<MessageReaction> =
    runCatching { messageEntityJson.decodeFromString(ListSerializer(MessageReaction.serializer()), text) }.getOrDefault(emptyList())
