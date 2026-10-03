package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendRemindFields(
    val chatId: String,
    val text: String,
)

internal sealed interface BotSendRemindFieldsResult {
    data class Ok(val fields: BotSendRemindFields) : BotSendRemindFieldsResult
    data object MissingRequired : BotSendRemindFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 抽取顺序：`chatId` → `text`（双别名 `text`→`message`），最后判 `chatId.isBlank() || text.isBlank()`。
 */
internal fun parseBotSendRemindFields(obj: JsonObject): BotSendRemindFieldsResult {
    // 注意：chatId 无 trim；text 双别名（text → message，只有 text 键缺席才回退、
    // 显式 null 得字面 "null" 不回退）、无 trim、先取后截 300。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val text = (obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(300)
    if (chatId.isBlank() || text.isBlank()) return BotSendRemindFieldsResult.MissingRequired
    return BotSendRemindFieldsResult.Ok(BotSendRemindFields(chatId, text))
}

/**
 * 与原处理器逐字一致的提醒正文组装：`"REMIND: " + text`。
 */
internal fun buildBotRemindContent(text: String): String =
    "REMIND: " + text
