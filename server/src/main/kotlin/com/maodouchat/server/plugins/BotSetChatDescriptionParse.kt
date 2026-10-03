package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSetChatDescriptionFields(
    val chatId: String,
    val description: String,
)

internal sealed interface BotSetChatDescriptionFieldsResult {
    data class Ok(val fields: BotSetChatDescriptionFields) : BotSetChatDescriptionFieldsResult
    /** 400 `"chatId required"` 的全部前件：chatId 缺/空/纯空白。 */
    data object Invalid : BotSetChatDescriptionFieldsResult
    /** 400 `"description too long"` 的全部前件：trim 后 description 超 1200 字符。 */
    data object TooLong : BotSetChatDescriptionFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 别名回退 + trim + 双 400 校验。
 * 双字段端点：`chatId`（无 trim）+ `description`（`description` 优先、`announcement` 回退、
 * 按存在而非按非空、显式 null 得字面量 `"null"`、trim 在长度夹界之前）；
 * chatId 缺/空/纯空白 → Invalid（处理器侧 400 `"chatId required"`）；
 * trim 后 description 超 1200 字符 → TooLong（处理器侧 400 `"description too long"`）。
 */
internal fun parseBotSetChatDescriptionFields(obj: JsonObject): BotSetChatDescriptionFieldsResult {
    // 注意：两处抽取都在必填判空之前；description 别名回退只看存在性——空字符串 /
    // 显式 null 的 description 都不回退到 announcement；显式 null 经 ?.jsonPrimitive
    //（JsonNull 是 JsonPrimitive，不抛）得字面量 "null"，trim 后仍非空。
    // trim 在长度校验之前；chatId 判空在长度校验之前，与原处理器逐字一致。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val description = (obj["description"] ?: obj["announcement"])?.jsonPrimitive?.content.orEmpty().trim()
    if (chatId.isBlank()) return BotSetChatDescriptionFieldsResult.Invalid
    if (description.length > 1200) return BotSetChatDescriptionFieldsResult.TooLong
    return BotSetChatDescriptionFieldsResult.Ok(BotSetChatDescriptionFields(chatId, description))
}
