package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendHrFields(
    val chatId: String,
    val note: String,
)

internal sealed interface BotSendHrFieldsResult {
    data class Ok(val fields: BotSendHrFields) : BotSendHrFieldsResult
    data object MissingRequired : BotSendHrFieldsResult
}

internal fun parseBotSendHrFields(obj: JsonObject): BotSendHrFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：note 无默认值（缺省回 ""），不 trim，前导空格计入 200 上限；
    // 对象/数组型 text 的 ?.jsonPrimitive 抛 IllegalArgumentException（大声失败）。
    val note = obj["text"]?.jsonPrimitive?.content.orEmpty().take(200)
    if (chatId.isBlank()) {
        return BotSendHrFieldsResult.MissingRequired
    }
    return BotSendHrFieldsResult.Ok(BotSendHrFields(chatId, note))
}

/**
 * 与原处理器逐字一致的分隔线消息内容组装：
 * `note` 非空白时 `"---\n$note\n---"`，空白时 `"---"`。
 */
internal fun buildBotHrContent(note: String): String =
    if (note.isNotBlank()) "---\n$note\n---" else "---"
