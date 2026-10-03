package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendAlertFields(
    val chatId: String,
    val text: String,
    val level: String,
)

internal sealed interface BotSendAlertFieldsResult {
    data class Ok(val fields: BotSendAlertFields) : BotSendAlertFieldsResult
    data object MissingRequired : BotSendAlertFieldsResult
}

internal fun parseBotSendAlertFields(obj: JsonObject): BotSendAlertFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：text 别名链接在存在性上——显式 JSON null 不穿透到 message，
    // 得字面 "null"（原处理器逐字语义）；不 trim，take(300)。
    val text = (obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(300)
    // 注意：ifBlank 在 take 之前——缺省/空白回 "info"；显式 null 得字面 "null"
    //（非空白，ifBlank 不触发）；超长 level 先回退默认再截 16（原处理器逐字顺序）。
    val level = obj["level"]?.jsonPrimitive?.content.orEmpty().ifBlank { "info" }.take(16)
    if (chatId.isBlank() || text.isBlank()) {
        return BotSendAlertFieldsResult.MissingRequired
    }
    return BotSendAlertFieldsResult.Ok(BotSendAlertFields(chatId, text, level))
}

/**
 * 与原处理器逐字一致的告警消息内容组装：`"ALERT[$level]: $text"`。
 */
internal fun buildBotAlertContent(text: String, level: String): String =
    "ALERT[$level]: $text"
