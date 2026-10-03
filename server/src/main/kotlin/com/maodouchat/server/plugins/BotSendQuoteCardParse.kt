package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotQuoteCardFields(
    val chatId: String,
    val quote: String,
    val by: String,
)

internal sealed interface BotQuoteCardFieldsResult {
    data class Ok(val fields: BotQuoteCardFields) : BotQuoteCardFieldsResult
    data object MissingRequired : BotQuoteCardFieldsResult
}

/**
 * `sendQuoteCard` 的请求体解析。
 *
 * 抽取顺序与原处理器逐字一致：先 `chatId`、再 `quote`、最后 `by`，然后判必填——
 * 坏类型字段的抛错顺序也因此不变（对象/数组型已知字段的 `?.jsonPrimitive`
 * 抛 [IllegalArgumentException]，大声失败）。
 */
internal fun parseBotQuoteCardFields(obj: JsonObject): BotQuoteCardFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val quote = obj["quote"]?.jsonPrimitive?.content.orEmpty().take(200)
    val by = obj["by"]?.jsonPrimitive?.content.orEmpty().take(40)
    // 注意：双必填——其余字段缺省/空白不判缺（原处理器逐字如此）。
    if (chatId.isBlank() || quote.isBlank()) {
        return BotQuoteCardFieldsResult.MissingRequired
    }
    return BotQuoteCardFieldsResult.Ok(BotQuoteCardFields(chatId, quote, by))
}

/**
 * 与原处理器逐字一致的内容组装：`"> "` + quote + 空白 by 时无署名行，
 * 否则 `"\n— *" + by + "*"`。
 */
internal fun buildBotQuoteCardContent(quote: String, by: String): String {
    val attribution = if (by.isBlank()) "" else "\n— *$by*"
    return "> $quote$attribution"
}
