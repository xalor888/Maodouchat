package com.maodouchat.server.messaging.v2

import com.maodouchat.server.model.MessageResponse
import java.nio.ByteBuffer
import java.security.MessageDigest

// MessageDigest 非线程安全：ThreadLocal 每线程复用一个，取用前 reset 防脏状态。
internal val sha256ThreadLocal = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

internal fun ServiceMessagePublisher.digestService(message: MessageResponse, content: String): String {
    val digest = sha256ThreadLocal.get().apply { reset() }
    listOf(
        message.id,
        message.chatId,
        message.senderId,
        message.type,
        message.timestamp.toString(),
        content,
    ).forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal fun ServiceMessagePublisher.digestServiceEvent(
    id: String,
    conversationId: String,
    senderUserId: String,
    clientTimestamp: Long,
    content: String,
): String {
    val digest = sha256ThreadLocal.get().apply { reset() }
    listOf(id, conversationId, senderUserId, clientTimestamp.toString(), content).forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal fun ServiceMessagingV2Event.isValidServiceMutation(): Boolean = when (action) {
    ServiceMessagePublisher.ACTION_EDIT -> content != null && editedAt != null && reactionEmoji == null
    ServiceMessagePublisher.ACTION_DELETE -> content == null && editedAt == null && reactionEmoji == null
    ServiceMessagePublisher.ACTION_REACTION_SET -> content == null && editedAt == null && !reactionEmoji.isNullOrBlank()
    else -> false
}
