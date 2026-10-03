package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSetChatTitleFields(
    val chatId: String,
    val title: String,
)

internal sealed interface BotSetChatTitleFieldsResult {
    data class Ok(val fields: BotSetChatTitleFields) : BotSetChatTitleFieldsResult
    /** 400 `"chatId/title required (1-50)"` 的全部前件：chatId 缺/空/纯空白，或 title 缺/空/纯空白，或 trim 后 title 超 50 字符。 */
    data object Invalid : BotSetChatTitleFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 归一化 + 合并校验。
 * 双字段端点：`chatId`（无 trim）+ `title`（`title` 优先、`groupName` 回退、trim 在先、长度夹界在后）。
 */
internal fun parseBotSetChatTitleFields(obj: JsonObject): BotSetChatTitleFieldsResult {
    // 注意：chatId 无 trim；title 优先于 groupName；任一键显式 null → 字面量 "null"（不回退、不判空）。
    // trim 在长度校验之前；任一缺/空/纯空白或 title 超 50 → Invalid（处理器侧 400 "chatId/title required (1-50)"）。
    // 抽取顺序 chatId 先、title 后，与原处理器逐字一致。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val title = (obj["title"] ?: obj["groupName"])?.jsonPrimitive?.content.orEmpty().trim()
    if (chatId.isBlank() || title.isBlank() || title.length > 50) return BotSetChatTitleFieldsResult.Invalid
    return BotSetChatTitleFieldsResult.Ok(BotSetChatTitleFields(chatId, title))
}
