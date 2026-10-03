package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotBannerFields(
    val chatId: String,
    val title: String,
    val text: String,
)

internal sealed interface BotBannerFieldsResult {
    data class Ok(val fields: BotBannerFields) : BotBannerFieldsResult
    data object MissingRequired : BotBannerFieldsResult
}

internal fun parseBotBannerFields(obj: JsonObject): BotBannerFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val title = obj["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Banner" }.take(40)
    val text = (obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(240)
    // 注意：双必填——title 缺省/空白不判缺（回 "Banner"，原处理器逐字如此）。
    if (chatId.isBlank() || text.isBlank()) {
        return BotBannerFieldsResult.MissingRequired
    }
    return BotBannerFieldsResult.Ok(BotBannerFields(chatId, title, text))
}

/**
 * 与原处理器逐字一致的内容组装：`"## " + title + "\n" + text`。
 */
internal fun buildBotBannerContent(title: String, text: String): String =
    "## " + title + "\n" + text
