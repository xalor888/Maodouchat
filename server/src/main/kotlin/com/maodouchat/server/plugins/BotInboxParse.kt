package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class BotInboxFields(
    val text: String,
    val botIdHint: String?,
)

/**
 * 与原处理器逐字一致的 `text` / `botIdHint` 抽取。
 * 注意 `as? JsonPrimitive` 的静默转型：非 primitive 值不抛，
 * `text` 得 `""`、`botIdHint` 得 `null`。
 */
internal fun parseBotInboxFields(obj: JsonObject): BotInboxFields {
    val text = (obj["text"] as? JsonPrimitive)?.content.orEmpty()
    val botIdHint = (obj["botId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    return BotInboxFields(text, botIdHint)
}
