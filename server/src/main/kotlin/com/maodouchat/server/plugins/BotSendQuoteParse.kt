package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotQuoteFields(
    val chatId: String,
    val quote: String,
    val note: String,
)

internal sealed interface BotQuoteFieldsResult {
    data class Ok(val fields: BotQuoteFields) : BotQuoteFieldsResult
    data object MissingRequired : BotQuoteFieldsResult
}

internal fun parseBotQuoteFields(obj: JsonObject): BotQuoteFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val quote = (obj["quote"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(1500)
    val note = obj["note"]?.jsonPrimitive?.content.orEmpty().take(500)
    // 注意：双必填——quote 判的是裁掉 1500 字符的空白性；note 不参与必填
    // （原处理器逐字如此）。
    if (chatId.isBlank() || quote.isBlank()) {
        return BotQuoteFieldsResult.MissingRequired
    }
    return BotQuoteFieldsResult.Ok(BotQuoteFields(chatId, quote, note))
}

/**
 * 与原处理器逐字一致的内容组装：逐行加 `"> "` 前缀；`note` 非空时以
 * `"\n\n"` 拼在引用块之后。
 */
internal fun buildBotQuoteContent(quote: String, note: String): String {
    val quoted = quote.lines().joinToString("\n") { "> " + it }
    return if (note.isNotBlank()) quoted + "\n\n" + note else quoted
}
