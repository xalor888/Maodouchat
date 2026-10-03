package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSetMyDescriptionFields(
    val description: String?,
)

internal sealed interface BotSetMyDescriptionFieldsResult {
    data class Ok(val fields: BotSetMyDescriptionFields) : BotSetMyDescriptionFieldsResult
}

/**
 * 与原处理器逐字一致的简介抽取 + 别名回退。
 * 单字段端点：`description` 优先、`about` 回退（按存在而非按非空；显式 null 不回退→
 * 字面量 `"null"`）；双缺席 → `null`（下游清简介语义不变）。
 */
internal fun parseBotSetMyDescriptionFields(obj: JsonObject): BotSetMyDescriptionFieldsResult {
    // 注意：?: 回退只看存在性——空字符串 / 显式 null 的 description 都不回退到 about；
    // 显式 null 经 ?.jsonPrimitive（JsonNull 是 JsonPrimitive，不抛）得字面量 "null"。
    val description = (obj["description"] ?: obj["about"])?.jsonPrimitive?.content
    return BotSetMyDescriptionFieldsResult.Ok(BotSetMyDescriptionFields(description))
}
