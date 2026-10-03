package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendCountdownFields(
    val chatId: String,
    val title: String,
    val seconds: Int,
)

internal sealed interface BotSendCountdownFieldsResult {
    data class Ok(val fields: BotSendCountdownFields) : BotSendCountdownFieldsResult
    data object MissingRequired : BotSendCountdownFieldsResult
}

internal fun parseBotSendCountdownFields(obj: JsonObject): BotSendCountdownFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：ifBlank 在 take 之前——缺省/空白回 "Countdown"；显式 null 得字面 "null"
    //（非空白，ifBlank 不触发）；不 trim，前导空格计入 40 上限（原处理器逐字顺序）。
    val title = obj["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Countdown" }.take(40)
    // 注意：toIntOrNull() 失败→回退 60（不报错，特意钉住）；
    // 对象/数组型 seconds 的 ?.jsonPrimitive 抛 IllegalArgumentException（大声失败）。
    val seconds = (obj["seconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 60).coerceIn(5, 86400)
    if (chatId.isBlank()) {
        return BotSendCountdownFieldsResult.MissingRequired
    }
    return BotSendCountdownFieldsResult.Ok(BotSendCountdownFields(chatId, title, seconds))
}

/**
 * 与原处理器逐字一致的倒计时消息内容组装：`"**$title**\n`T-${seconds}s`"`。
 */
internal fun buildBotCountdownContent(title: String, seconds: Int): String =
    "**$title**\n`T-${seconds}s`"
