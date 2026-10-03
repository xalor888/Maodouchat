package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotCodeFields(
    val chatId: String,
    val code: String,
    val lang: String,
)

internal sealed interface BotCodeFieldsResult {
    data class Ok(val fields: BotCodeFields) : BotCodeFieldsResult
    data object MissingRequired : BotCodeFieldsResult
}

internal fun parseBotCodeFields(obj: JsonObject): BotCodeFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val code = (obj["code"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(3500)
    val lang = obj["language"]?.jsonPrimitive?.content.orEmpty().take(24)
    // 注意：双必填——code 判的是裁掉 3500 字符的空白性；language 不参与必填
    // （原处理器逐字如此）。
    if (chatId.isBlank() || code.isBlank()) {
        return BotCodeFieldsResult.MissingRequired
    }
    return BotCodeFieldsResult.Ok(BotCodeFields(chatId, code, lang))
}

/**
 * 与原处理器逐字一致的围栏组装：`lang` 非空（`isNotBlank`，判的是裁过 24 的串）
 * 时 `` ``` `` + lang + `"\n"` + code + `"\n```"`，否则 `` ``` `` + `"\n"` +
 * code + `"\n```"`。
 */
internal fun buildBotCodeContent(code: String, lang: String): String =
    if (lang.isNotBlank()) "```" + lang + "\n" + code + "\n```"
    else "```\n" + code + "\n```"
