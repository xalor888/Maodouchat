package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotBanChatMemberFields(
    val chatId: String,
    val userId: String,
)

internal sealed interface BotBanChatMemberFieldsResult {
    data class Ok(val fields: BotBanChatMemberFields) : BotBanChatMemberFieldsResult
    /** 400 `"chatId/userId required"` 的全部前件：chatId 缺/空/纯空白，或 userId 缺/空/纯空白。 */
    data object Invalid : BotBanChatMemberFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 双字段端点：`chatId`（无 trim）+ `userId`（无 trim）；任一缺/空/纯空白 → Invalid
 * （处理器侧 400 "chatId/userId required"）。
 */
internal fun parseBotBanChatMemberFields(obj: JsonObject): BotBanChatMemberFieldsResult {
    // 注意：两字段均无 trim；任一键显式 null → 字面量 "null"（不判空）；抽取顺序 chatId 先、userId 后。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val userId = obj["userId"]?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || userId.isBlank()) return BotBanChatMemberFieldsResult.Invalid
    return BotBanChatMemberFieldsResult.Ok(BotBanChatMemberFields(chatId, userId))
}
