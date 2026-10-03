package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotUnpinAllChatMessagesFields(
    val chatId: String,
)

internal sealed interface BotUnpinAllChatMessagesFieldsResult {
    data class Ok(val fields: BotUnpinAllChatMessagesFields) : BotUnpinAllChatMessagesFieldsResult
    data object MissingRequired : BotUnpinAllChatMessagesFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 单字段端点：只有 `chatId`（无缺省/夹界逻辑）。
 */
internal fun parseBotUnpinAllChatMessagesFields(obj: JsonObject): BotUnpinAllChatMessagesFieldsResult {
    // 注意：chatId 无 trim；缺/空/纯空白 → MissingRequired（处理器侧 400 "chatId required"）。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank()) return BotUnpinAllChatMessagesFieldsResult.MissingRequired
    return BotUnpinAllChatMessagesFieldsResult.Ok(BotUnpinAllChatMessagesFields(chatId))
}
