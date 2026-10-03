package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotKeyValueFields(
    val chatId: String,
    val key: String,
    val value: String,
)

internal sealed interface BotKeyValueFieldsResult {
    data class Ok(val fields: BotKeyValueFields) : BotKeyValueFieldsResult
    data object MissingRequired : BotKeyValueFieldsResult
}

/**
 * `sendKeyValue` 的请求体解析。
 *
 * 抽取顺序与原处理器逐字一致：先 `chatId`、再 `key`、最后 `value`，然后判必填——
 * 坏类型字段的抛错顺序也因此不变（对象/数组型已知字段的 `?.jsonPrimitive`
 * 抛 [IllegalArgumentException]，大声失败）。
 */
internal fun parseBotKeyValueFields(obj: JsonObject): BotKeyValueFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：key 的默认值接空白性——缺席与空白都得 "key"（"null" 字面等非空白串不触发）；
    // value 无默认值——缺席得 ""，空白原样保留。
    val key = obj["key"]?.jsonPrimitive?.content.orEmpty().ifBlank { "key" }.take(40)
    val value = obj["value"]?.jsonPrimitive?.content.orEmpty().take(120)
    // 注意：单必填——其余字段缺省/空白不判缺（原处理器逐字如此）。
    if (chatId.isBlank()) {
        return BotKeyValueFieldsResult.MissingRequired
    }
    return BotKeyValueFieldsResult.Ok(BotKeyValueFields(chatId, key, value))
}

/**
 * 与原处理器逐字一致的内容组装：`"`" + key + "` = **" + value + "**"`。
 */
internal fun buildBotKeyValueContent(key: String, value: String): String =
    "`" + key + "` = **" + value + "**"
