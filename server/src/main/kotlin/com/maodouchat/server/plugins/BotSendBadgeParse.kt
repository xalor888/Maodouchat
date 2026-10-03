package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendBadgeFields(
    val chatId: String,
    val label: String,
    val value: String,
)

internal sealed interface BotSendBadgeFieldsResult {
    data class Ok(val fields: BotSendBadgeFields) : BotSendBadgeFieldsResult
    data object MissingRequired : BotSendBadgeFieldsResult
}

internal fun parseBotSendBadgeFields(obj: JsonObject): BotSendBadgeFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：ifBlank 在 take 之前——缺省/空白回 "badge"；显式 null 得字面 "null"
    //（非空白，ifBlank 不触发）；不 trim，前导空格计入 40 上限（原处理器逐字顺序）。
    val label = obj["label"]?.jsonPrimitive?.content.orEmpty().ifBlank { "badge" }.take(40)
    // 注意：value 无默认值（缺省回 ""），不 trim，前导空格计入 80 上限；
    // 对象/数组型 value 的 ?.jsonPrimitive 抛 IllegalArgumentException（大声失败）。
    val value = obj["value"]?.jsonPrimitive?.content.orEmpty().take(80)
    if (chatId.isBlank()) {
        return BotSendBadgeFieldsResult.MissingRequired
    }
    return BotSendBadgeFieldsResult.Ok(BotSendBadgeFields(chatId, label, value))
}

/**
 * 与原处理器逐字一致的徽章消息内容组装：`"**$label**: `$value`"`。
 */
internal fun buildBotBadgeContent(label: String, value: String): String =
    "**$label**: `$value`"
