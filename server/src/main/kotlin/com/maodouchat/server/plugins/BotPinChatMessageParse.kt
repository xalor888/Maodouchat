package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotPinChatMessageFields(
    val chatId: String,
    val messageId: String,
)

internal sealed interface BotPinChatMessageFieldsResult {
    data class Ok(val fields: BotPinChatMessageFields) : BotPinChatMessageFieldsResult
    data object MissingRequired : BotPinChatMessageFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 双字段端点：`chatId` + `messageId`（无缺省/夹界逻辑；任一缺/空/纯空白→MissingRequired）。
 */
internal fun parseBotPinChatMessageFields(obj: JsonObject): BotPinChatMessageFieldsResult {
    // 注意：chatId / messageId 均无 trim；缺/空/纯空白 → MissingRequired
    // （处理器侧 400 "chatId/messageId required"）。抽取顺序 chatId 先、messageId 后，与原处理器逐字一致。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || messageId.isBlank()) return BotPinChatMessageFieldsResult.MissingRequired
    return BotPinChatMessageFieldsResult.Ok(BotPinChatMessageFields(chatId, messageId))
}
