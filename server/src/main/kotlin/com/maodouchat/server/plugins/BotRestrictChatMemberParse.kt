package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotRestrictChatMemberFields(
    val chatId: String,
    val userId: String,
    val until: Long,
)

internal sealed interface BotRestrictChatMemberFieldsResult {
    data class Ok(val fields: BotRestrictChatMemberFields) : BotRestrictChatMemberFieldsResult
    /** 400 `"chatId/userId required"` 的全部前件：chatId 缺/空/纯空白，或 userId 缺/空/纯空白。 */
    data object Invalid : BotRestrictChatMemberFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 双字段必填（`chatId` 无 trim、`userId` 无 trim；任一缺/空/纯空白 → Invalid，
 * 处理器侧 400 "chatId/userId required"）+ 可选 `until`（`untilDate`→`mutedUntil`→0L）。
 */
internal fun parseBotRestrictChatMemberFields(obj: JsonObject): BotRestrictChatMemberFieldsResult {
    // 注意：两字段均无 trim；任一键显式 null → 字面量 "null"（不判空）；抽取顺序 chatId 先、userId 后。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val userId = obj["userId"]?.jsonPrimitive?.content.orEmpty()
    // until 的抽取必须先于必填校验：坏类型 untilDate 在这里大声失败，与原处理器顺序逐字。
    val until = obj["untilDate"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: obj["mutedUntil"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: 0L
    if (chatId.isBlank() || userId.isBlank()) return BotRestrictChatMemberFieldsResult.Invalid
    return BotRestrictChatMemberFieldsResult.Ok(BotRestrictChatMemberFields(chatId, userId, until))
}
