package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendProgressFields(
    val chatId: String,
    val title: String,
    val percent: Int,
)

internal sealed interface BotSendProgressFieldsResult {
    data class Ok(val fields: BotSendProgressFields) : BotSendProgressFieldsResult
    data object MissingRequired : BotSendProgressFieldsResult
}

internal fun parseBotSendProgressFields(obj: JsonObject): BotSendProgressFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：ifBlank 在 take 之前——缺省/空白回 "Progress"；显式 null 得字面 "null"
    //（非空白，ifBlank 不触发）；不 trim，前导空格计入 40 上限（原处理器逐字顺序）。
    val title = obj["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Progress" }.take(40)
    // 注意：字符串解析 + 钳制——缺省/显式 null/非数字/小数一律回 0，再 coerceIn(0, 100)
    //（原处理器逐字如此）。
    val percent = (obj["percent"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceIn(0, 100)
    if (chatId.isBlank()) {
        return BotSendProgressFieldsResult.MissingRequired
    }
    return BotSendProgressFieldsResult.Ok(BotSendProgressFields(chatId, title, percent))
}

/**
 * 与原处理器逐字一致的进度条消息内容组装。
 *
 * 前置条件：`percent` 须已钳制在 0..100（[parseBotSendProgressFields] 保证）——
 * `filled = percent / 10` 为整数除法，`bar` 为 10 格 `"#" + "-"`。
 */
internal fun buildBotProgressContent(title: String, percent: Int): String {
    val filled = percent / 10
    // 注：刻意把 repeat 提到模板外，避免模板内嵌套引号（Kotlin 2.4.0 K2 下报 Syntax error）。
    val hashes = "#".repeat(filled)
    val dashes = "-".repeat(10 - filled)
    val bar = hashes + dashes
    return "**$title**\n`[$bar]` $percent%"
}
