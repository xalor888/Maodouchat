package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageReaction
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val messageReactionJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// 消息 Reaction 列表与 JSON 文本互转：解析失败兜底空列表，编码失败兜底 "[]"。
object MessageReactionJsonPolicy {

    fun encodeReactions(reactions: List<MessageReaction>): String =
        runCatching { messageReactionJson.encodeToString(ListSerializer(MessageReaction.serializer()), reactions) }.getOrDefault("[]")

    fun decodeReactions(text: String): List<MessageReaction> =
        runCatching { messageReactionJson.decodeFromString(ListSerializer(MessageReaction.serializer()), text) }.getOrDefault(emptyList())
}
