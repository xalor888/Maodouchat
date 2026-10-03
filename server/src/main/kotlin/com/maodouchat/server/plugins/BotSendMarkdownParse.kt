package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class BotMarkdownFields(
    val chatId: String,
    val text: String,
    /** 原始 `silent` 请求值；是否真正静默发送仍由处理器结合运行时配置决定。 */
    val silentRequested: Boolean,
)

internal sealed interface BotMarkdownFieldsResult {
    data class Ok(val fields: BotMarkdownFields) : BotMarkdownFieldsResult
    data object MissingRequired : BotMarkdownFieldsResult
}

internal fun parseBotMarkdownFields(obj: JsonObject): BotMarkdownFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val text = (obj["text"] ?: obj["markdown"])?.jsonPrimitive?.content.orEmpty().take(4000)
    val silentRequested = obj["silent"]?.jsonPrimitive?.booleanOrNull == true
    // 注意：双必填——text 判的是裁掉 4000 字符的空白性（原处理器逐字如此）。
    if (chatId.isBlank() || text.isBlank()) {
        return BotMarkdownFieldsResult.MissingRequired
    }
    return BotMarkdownFieldsResult.Ok(BotMarkdownFields(chatId, text, silentRequested))
}
