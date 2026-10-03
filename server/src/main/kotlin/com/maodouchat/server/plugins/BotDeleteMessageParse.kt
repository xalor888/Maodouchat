package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotDeleteMessageFields(
    val chatId: String,
    val messageId: String,
)

internal sealed interface BotDeleteMessageFieldsResult {
    data class Ok(val fields: BotDeleteMessageFields) : BotDeleteMessageFieldsResult
    /** 400 `"chatId/messageId required"` 的全部前件：chatId 缺/空/纯空白，或 messageId 缺/空/纯空白。 */
    data object Invalid : BotDeleteMessageFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 双字段端点：`chatId`（无 trim）+ `messageId`（无 trim）；任一缺/空/纯空白 → Invalid
 * （处理器侧 400 "chatId/messageId required"）。
 */
internal fun parseBotDeleteMessageFields(obj: JsonObject): BotDeleteMessageFieldsResult {
    // 注意：两字段均无 trim；任一键显式 null → 字面量 "null"（不判空）；抽取顺序 chatId 先、messageId 后。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || messageId.isBlank()) return BotDeleteMessageFieldsResult.Invalid
    return BotDeleteMessageFieldsResult.Ok(BotDeleteMessageFields(chatId, messageId))
}
