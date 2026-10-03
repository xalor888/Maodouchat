package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotChatCallbackFields(
    val messageId: String,
    val botUserId: String,
    val callbackData: String,
)

internal sealed interface BotChatCallbackFieldsResult {
    data class Ok(val fields: BotChatCallbackFields) : BotChatCallbackFieldsResult
    /**
     * 400 `"messageId/botUserId/callbackData required"` 的全部前件：
     * 任一字段缺/空/纯空白，或 `messageId`/`botUserId` 超 80、`callbackData` 超 128。
     */
    data object Invalid : BotChatCallbackFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填与超长校验。
 * `messageId`/`botUserId`（无 trim；显式 null 得字面量 `"null"`；上限 80）、
 * `callbackData`（无 trim；显式 null 得字面量 `"null"`；上限 128）——
 * 任一缺/空/纯空白或超长 → Invalid（处理器侧 400
 * `"messageId/botUserId/callbackData required"`）。
 */
internal fun parseBotChatCallbackFields(obj: JsonObject): BotChatCallbackFieldsResult {
    // 注意：无 trim；三键显式 null → 字面量 "null"（不判空）；对象/数组型在
    // ?.jsonPrimitive 处大声失败。
    val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
    val botUserId = obj["botUserId"]?.jsonPrimitive?.content.orEmpty()
    val callbackData = obj["callbackData"]?.jsonPrimitive?.content.orEmpty()
    if (messageId.isBlank() || messageId.length > 80 ||
        botUserId.isBlank() || botUserId.length > 80 ||
        callbackData.isBlank() || callbackData.length > 128
    ) {
        return BotChatCallbackFieldsResult.Invalid
    }
    return BotChatCallbackFieldsResult.Ok(BotChatCallbackFields(messageId, botUserId, callbackData))
}
