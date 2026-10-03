package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotCreateFields(
    val name: String,
    val username: String,
    val description: String?,
)

/**
 * 与原处理器逐字一致的请求体抽取。
 * `name`/`username`（无 trim；缺席→空串；显式 null→字面量 `"null"`）+
 * `description`（无 trim；缺席→null；显式 null→字面量 `"null"`）。
 */
internal fun parseBotCreateFields(obj: JsonObject): BotCreateFields {
    // 注意：三字段均无 trim；缺席/显式 null 语义逐字；对象/数组型在
    // ?.jsonPrimitive 处大声失败。抽取顺序 name → username → description。
    val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
    val username = obj["username"]?.jsonPrimitive?.content.orEmpty()
    val description = obj["description"]?.jsonPrimitive?.content
    return BotCreateFields(name, username, description)
}
