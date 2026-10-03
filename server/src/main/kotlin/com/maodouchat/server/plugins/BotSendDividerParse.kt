package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendDividerFields(
    val chatId: String,
    val label: String,
)

internal sealed interface BotSendDividerFieldsResult {
    data class Ok(val fields: BotSendDividerFields) : BotSendDividerFieldsResult
    data object MissingRequired : BotSendDividerFieldsResult
}

internal fun parseBotSendDividerFields(obj: JsonObject): BotSendDividerFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：ifBlank 在 take 之前——缺省/空白回 "divider"；显式 null 得字面 "null"
    //（非空白，ifBlank 不触发）；不 trim，前导空格计入 40 上限（原处理器逐字顺序）。
    val label = obj["label"]?.jsonPrimitive?.content.orEmpty().ifBlank { "divider" }.take(40)
    if (chatId.isBlank()) {
        return BotSendDividerFieldsResult.MissingRequired
    }
    return BotSendDividerFieldsResult.Ok(BotSendDividerFields(chatId, label))
}

/**
 * 与原处理器逐字一致的分隔线消息内容组装：`"---\n**$label**\n---"`。
 */
internal fun buildBotDividerContent(label: String): String =
    "---\n**$label**\n---"
