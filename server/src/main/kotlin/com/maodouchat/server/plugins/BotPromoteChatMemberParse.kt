package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotPromoteChatMemberFields(
    val chatId: String,
    val userId: String,
    val role: String,
)

internal sealed interface BotPromoteChatMemberFieldsResult {
    data class Ok(val fields: BotPromoteChatMemberFields) : BotPromoteChatMemberFieldsResult
    /** 400 `"chatId/userId required"` 的全部前件：chatId 缺/空/纯空白，或 userId 缺/空/纯空白。 */
    data object Invalid : BotPromoteChatMemberFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 角色归一化 + 合并必填校验。
 * 三字段端点：`chatId`（无 trim）+ `userId`（无 trim）+ `role`（无 trim、上大写后精确判 `"MEMBER"`）；
 * 任一 chatId/userId 缺/空/纯空白 → Invalid（处理器侧 400 "chatId/userId required"）。
 */
internal fun parseBotPromoteChatMemberFields(obj: JsonObject): BotPromoteChatMemberFieldsResult {
    // 注意：role 的抽取与归一化在空白判空之前；显式 null → 字面量 "null"（不判空）。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val userId = obj["userId"]?.jsonPrimitive?.content.orEmpty()
    val roleRaw = obj["role"]?.jsonPrimitive?.content.orEmpty().uppercase().ifBlank { "ADMIN" }
    val role = if (roleRaw == "MEMBER") "MEMBER" else "ADMIN"
    if (chatId.isBlank() || userId.isBlank()) return BotPromoteChatMemberFieldsResult.Invalid
    return BotPromoteChatMemberFieldsResult.Ok(BotPromoteChatMemberFields(chatId, userId, role))
}
