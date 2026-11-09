package com.maodouchat.domain.messaging

import com.maodouchat.core.model.LocationPayload
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import kotlinx.serialization.json.Json

// 特殊附件乐观消息组装：贴纸/位置/名片三路在无 commandFacade 时的本地 Message 构造，
// 逐字平移 DefaultAttachmentIntentController 的三条分支。nowMs 同时用作位置 capturedAt
// 与消息时间戳（原实现是两次相邻的 currentTimeMillis，同一时刻）。
object SpecialAttachmentMessagePolicy {

    fun resolveMessageId(
        idempotencyKey: String,
        fallbackId: () -> String = { "m_${java.util.UUID.randomUUID()}" },
    ): String = idempotencyKey.ifBlank { fallbackId() }

    fun stickerMessage(
        messageId: String,
        conversationId: String,
        ownerUserId: String,
        stickerCode: String,
        nowMs: Long,
    ): Message = Message(
        id = messageId,
        chatId = conversationId,
        senderId = ownerUserId,
        content = stickerCode,
        type = MessageType.STICKER,
        timestamp = nowMs,
        status = MessageStatus.SENDING,
    )

    fun locationMessage(
        messageId: String,
        conversationId: String,
        ownerUserId: String,
        location: GeoLocation,
        nowMs: Long,
    ): Message {
        val payload = LocationPayload(
            latitude = location.latitude,
            longitude = location.longitude,
            capturedAt = nowMs,
        )
        return Message(
            id = messageId,
            chatId = conversationId,
            senderId = ownerUserId,
            content = Json.encodeToString(LocationPayload.serializer(), payload),
            type = MessageType.LOCATION,
            timestamp = nowMs,
            status = MessageStatus.SENDING,
        )
    }

    fun contactMessage(
        messageId: String,
        conversationId: String,
        ownerUserId: String,
        card: ContactCard,
        nowMs: Long,
    ): Message = Message(
        id = messageId,
        chatId = conversationId,
        senderId = ownerUserId,
        content = "👤 ${card.displayName}\n[contactUser:${card.userId}]",
        type = MessageType.TEXT,
        timestamp = nowMs,
        status = MessageStatus.SENDING,
    )
}
