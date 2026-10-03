package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotEchoFields(
    val text: String,
)

/**
 * 与原处理器逐字一致的 `echo` 字段抽取（`text`→`message` 别名链、take(500) 不 trim、
 * 无必填校验——故直接返回字段、无需 Result）。
 */
internal fun parseBotEchoFields(obj: JsonObject): BotEchoFields {
    // 注意：?: 接在字段存在性上——text 键存在（哪怕是显式 JSON null）就不穿透到
    // message，得字面量 "null"（原处理器逐字语义）；两键都缺才回 ""。
    val text = (obj["text"] ?: obj["message"])?.jsonPrimitive?.content.orEmpty().take(500)
    return BotEchoFields(text)
}
