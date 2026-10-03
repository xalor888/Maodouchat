package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSetMyNameFields(
    val name: String,
)

internal sealed interface BotSetMyNameFieldsResult {
    data class Ok(val fields: BotSetMyNameFields) : BotSetMyNameFieldsResult
    /** 400 `"invalid name"` 的全部前件：缺 / 空 / 纯空白（trim 之后）。 */
    data object InvalidName : BotSetMyNameFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 归一化 + 必填校验。
 * 单字段端点：`name` 优先、`displayName` 回退（显式 null 不回退→`"null"` 怪语义）；
 * trim 后判空，超长 `.take(120)`。
 */
internal fun parseBotSetMyNameFields(obj: JsonObject): BotSetMyNameFieldsResult {
    // 注意：name 优先于 displayName；任一键显式 null → 字面量 "null"（不回退）。
    // trim 在 take(120) 之前；缺 / 空 / 纯空白 → InvalidName（处理器侧 400 "invalid name"）。
    val name = (obj["name"] ?: obj["displayName"])?.jsonPrimitive?.content.orEmpty().trim().take(120)
    if (name.isBlank()) return BotSetMyNameFieldsResult.InvalidName
    return BotSetMyNameFieldsResult.Ok(BotSetMyNameFields(name))
}
