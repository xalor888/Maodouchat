package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotDeleteUpdatesFields(
    val upTo: Long,
)

internal sealed interface BotDeleteUpdatesFieldsResult {
    data class Ok(val fields: BotDeleteUpdatesFields) : BotDeleteUpdatesFieldsResult
    /** 400 `"upToId required"` 的全部前件：body/query 回退链全空或全非法→缺省 `0L`，或解析出 `<= 0L`。 */
    data object Invalid : BotDeleteUpdatesFieldsResult
}

/**
 * 与原处理器逐字一致的三层回退 + 必填校验：body `upToId` 优先、body `offset` 兜底、
 * query `upToId`（调用方用 parseRawOrNull 传入）再兜底、缺省 `0L`；
 * 回退只看「能否解析出 Long」；`upTo <= 0L`→Invalid（处理器侧 400 `"upToId required"`）。
 */
internal fun parseBotDeleteUpdatesFields(
    obj: JsonObject?,
    queryUpToId: String?,
): BotDeleteUpdatesFieldsResult {
    // 三层回退都在必填判空之前；显式 null upToId → 字面量 "null"→toLongOrNull 得 null→继续回退。
    // 对象/数组型 upToId 会在 jsonPrimitive 直接抛（与原处理器一致）。
    val upTo = obj?.get("upToId")?.jsonPrimitive?.content?.toLongOrNull()
        ?: obj?.get("offset")?.jsonPrimitive?.content?.toLongOrNull()
        ?: queryUpToId?.toLongOrNull()
        ?: 0L
    if (upTo <= 0L) return BotDeleteUpdatesFieldsResult.Invalid
    return BotDeleteUpdatesFieldsResult.Ok(BotDeleteUpdatesFields(upTo))
}
