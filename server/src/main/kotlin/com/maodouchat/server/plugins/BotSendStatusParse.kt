package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendStatusFields(
    val chatId: String,
    val text: String,
)

internal sealed interface BotSendStatusFieldsResult {
    data class Ok(val fields: BotSendStatusFields) : BotSendStatusFieldsResult
    data object MissingRequired : BotSendStatusFieldsResult
}

internal fun parseBotSendStatusFields(obj: JsonObject): BotSendStatusFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：无 trim()；text 先 take(200) 再判空白；text 缺键才回退 status。
    val text = (obj["text"] ?: obj["status"])?.jsonPrimitive?.content.orEmpty().take(200)
    if (chatId.isBlank() || text.isBlank()) {
        return BotSendStatusFieldsResult.MissingRequired
    }
    return BotSendStatusFieldsResult.Ok(BotSendStatusFields(chatId, text))
}
