package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotJsonCardFields(
    val chatId: String,
    val payload: String,
)

internal sealed interface BotJsonCardFieldsResult {
    data class Ok(val fields: BotJsonCardFields) : BotJsonCardFieldsResult
    data object MissingRequired : BotJsonCardFieldsResult
}

internal fun parseBotJsonCardFields(obj: JsonObject): BotJsonCardFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val payload = (obj["json"] ?: obj["data"])?.toString()?.take(500).orEmpty()
    // 注意：双必填——payload 判的是序列化后裁掉 500 字符的空白性（原处理器逐字如此）。
    if (chatId.isBlank() || payload.isBlank()) {
        return BotJsonCardFieldsResult.MissingRequired
    }
    return BotJsonCardFieldsResult.Ok(BotJsonCardFields(chatId, payload))
}

/**
 * 与原处理器逐字一致的内容组装：`"```json\n" + payload + "\n```"`。
 */
internal fun buildBotJsonCardContent(payload: String): String =
    "```json\n" + payload + "\n```"
