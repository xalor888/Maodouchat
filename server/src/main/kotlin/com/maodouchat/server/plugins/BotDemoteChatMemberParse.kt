package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotDemoteChatMemberFields(
    val chatId: String,
    val userId: String,
)

internal sealed interface BotDemoteChatMemberFieldsResult {
    data class Ok(val fields: BotDemoteChatMemberFields) : BotDemoteChatMemberFieldsResult
    /** 400 `"chatId/userId required"` 的全部前件：chatId 缺/空/纯空白，或 userId 缺/空/纯空白。 */
    data object Invalid : BotDemoteChatMemberFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 双字段端点：`chatId`（无 trim）+ `userId`（无 trim）；
 * 任一 chatId/userId 缺/空/纯空白 → Invalid（处理器侧 400 "chatId/userId required"）。
 */
internal fun parseBotDemoteChatMemberFields(obj: JsonObject): BotDemoteChatMemberFieldsResult {
    // 注意：两处抽取都在空白判空之前；显式 null → 字面量 "null"（不判空）。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val userId = obj["userId"]?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || userId.isBlank()) return BotDemoteChatMemberFieldsResult.Invalid
    return BotDemoteChatMemberFieldsResult.Ok(BotDemoteChatMemberFields(chatId, userId))
}
