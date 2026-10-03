package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendMessageSilentFields(
    val chatId: String,
    val text: String,
    val msgType: String,
)

internal sealed interface BotSendMessageSilentFieldsResult {
    data class Ok(val fields: BotSendMessageSilentFields) : BotSendMessageSilentFieldsResult
    data object MissingRequired : BotSendMessageSilentFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 抽取顺序：`chatId` → `text` → `parseMode`，最后判 `chatId.isBlank() || text.isBlank()`。
 */
internal fun parseBotSendMessageSilentFields(obj: JsonObject): BotSendMessageSilentFieldsResult {
    // 注意：chatId/text 无 trim；text 先取后截 4000；parseMode 无 trim 直接 uppercase，
    // 只有大写后恰好 "MARKDOWN"/"MD" 才得 "MARKDOWN"，其余一律 "TEXT"。
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val text = obj["text"]?.jsonPrimitive?.content.orEmpty().take(4000)
    val parseMode = obj["parseMode"]?.jsonPrimitive?.content.orEmpty().uppercase()
    val msgType = when {
        parseMode == "MARKDOWN" || parseMode == "MD" -> "MARKDOWN"
        else -> "TEXT"
    }
    if (chatId.isBlank() || text.isBlank()) return BotSendMessageSilentFieldsResult.MissingRequired
    return BotSendMessageSilentFieldsResult.Ok(BotSendMessageSilentFields(chatId, text, msgType))
}
