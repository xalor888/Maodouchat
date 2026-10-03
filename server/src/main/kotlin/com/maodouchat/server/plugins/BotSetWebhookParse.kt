package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSetWebhookFields(
    val url: String?,
)

internal sealed interface BotSetWebhookFieldsResult {
    data class Ok(val fields: BotSetWebhookFields) : BotSetWebhookFieldsResult
    /** 400 `"invalid json"` 的前件：body 不是 JSON 对象（`obj` 为 null）。 */
    data object InvalidJson : BotSetWebhookFieldsResult
    /** 400 `"invalid webhook url"` 的前件：url 非空且白名单校验不通过。 */
    data object InvalidUrl : BotSetWebhookFieldsResult
}

/**
 * 与原处理器逐字一致的请求体抽取 + trim/take(500) + 白名单校验。
 * 单字段端点：`obj` 为 null（body 非 JSON 对象）→ InvalidJson；
 * `obj["url"]?.jsonPrimitive?.content?.trim()?.take(500)`（trim 先于 take；
 * 显式 null 得字面量 `"null"`；对象/数组型在 `?.jsonPrimitive` 处先抛）；
 * url 非空才走白名单校验，不通过 → InvalidUrl（处理器侧 400，文案逐字）。
 */
internal fun parseBotSetWebhookFields(
    obj: JsonObject?,
    isUrlAllowed: (String) -> Boolean,
): BotSetWebhookFieldsResult {
    if (obj == null) return BotSetWebhookFieldsResult.InvalidJson
    // 注意：抽取在白名单校验之前；显式 null url → 字面量 "null"（非空）→ 走校验；
    // 空字符串 / 纯空白跳过校验；对象/数组型 url 在 ?.jsonPrimitive 处先抛。
    val url = obj["url"]?.jsonPrimitive?.content?.trim()?.take(500)
    if (!url.isNullOrBlank() && !isUrlAllowed(url)) return BotSetWebhookFieldsResult.InvalidUrl
    return BotSetWebhookFieldsResult.Ok(BotSetWebhookFields(url))
}
