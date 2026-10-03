package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotForwardCopyFields(
    val fromChatId: String,
    val toChatId: String,
    val messageId: String,
)

internal sealed interface BotForwardCopyFieldsResult {
    data class Ok(val fields: BotForwardCopyFields) : BotForwardCopyFieldsResult
    data object MissingRequired : BotForwardCopyFieldsResult
}

/** 与原两个处理器逐字一致的转发 / 复制字段抽取（含三字段别名优先级与三必填校验）。 */
internal fun parseBotForwardCopyFields(obj: JsonObject): BotForwardCopyFieldsResult {
    // 注意：?: 接在 jsonPrimitive 之前——主字段存在即不穿透别名（哪怕显式 null），原处理器逐字语义。
    val fromChatId = (obj["fromChatId"] ?: obj["from_chat_id"])?.jsonPrimitive?.content.orEmpty()
    val toChatId = (obj["chatId"] ?: obj["toChatId"] ?: obj["to_chat_id"])?.jsonPrimitive?.content.orEmpty()
    val messageId = (obj["messageId"] ?: obj["message_id"])?.jsonPrimitive?.content.orEmpty()
    if (fromChatId.isBlank() || toChatId.isBlank() || messageId.isBlank()) {
        return BotForwardCopyFieldsResult.MissingRequired
    }
    return BotForwardCopyFieldsResult.Ok(BotForwardCopyFields(fromChatId, toChatId, messageId))
}
