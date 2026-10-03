package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendChatActionFields(
    val chatId: String,
    val action: String,
)

internal sealed interface BotSendChatActionFieldsResult {
    data class Ok(val fields: BotSendChatActionFields) : BotSendChatActionFieldsResult
    data object MissingRequired : BotSendChatActionFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 抽取顺序：`chatId` → `action`（先 lowercase 后 ifBlank 回退），最后判 `chatId.isBlank()`。
 */
internal fun parseBotSendChatActionFields(obj: JsonObject): BotSendChatActionFieldsResult {
    // 注意：chatId 无 trim；action 先 lowercase 后判空，缺席/空/纯空白→"typing"、
    // 显式 null 得字面 "null"（非空→不回退，逐字语义）；只有 chatId 参与必填。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val action = obj["action"]?.jsonPrimitive?.content.orEmpty().lowercase().ifBlank { "typing" }
    if (chatId.isBlank()) return BotSendChatActionFieldsResult.MissingRequired
    return BotSendChatActionFieldsResult.Ok(BotSendChatActionFields(chatId, action))
}
