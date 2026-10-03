package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotClosePollFields(
    val pollId: String,
)

internal sealed interface BotClosePollFieldsResult {
    data class Ok(val fields: BotClosePollFields) : BotClosePollFieldsResult
    /** 400 `\"pollId required\"` 的全部前件：pollId 缺/空/纯空白。 */
    data object Invalid : BotClosePollFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + 合并必填校验。
 * 单字段端点：`pollId`（无 trim）；缺/空/纯空白 → Invalid
 * （处理器侧 400 "pollId required"）。
 */
internal fun parseBotClosePollFields(obj: JsonObject): BotClosePollFieldsResult {
    // 注意：无 trim；pollId 键显式 null → 字面量 "null"（不判空）。
    val pollId = obj["pollId"]?.jsonPrimitive?.content.orEmpty()
    if (pollId.isBlank()) return BotClosePollFieldsResult.Invalid
    return BotClosePollFieldsResult.Ok(BotClosePollFields(pollId))
}
