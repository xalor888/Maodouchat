package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSetWebhookFields(
    val url: String?,
)

internal sealed interface BotSetWebhookFieldsResult {
    data class Ok(val fields: BotSetWebhookFields) : BotSetWebhookFieldsResult
    /** 400 `"invalid webhook url"` 的前件：url 非空且白名单校验不通过。 */
    data object InvalidUrl : BotSetWebhookFieldsResult
}

/** 抽取 url 并 trim/take(500) 后走白名单；调用方保证 obj 非空（坏 body 已由 requireJsonObjectOr400 拦掉）。 */
internal fun parseBotSetWebhookFields(
    obj: JsonObject,
    isUrlAllowed: (String) -> Boolean,
): BotSetWebhookFieldsResult {
    // 注意：抽取在白名单校验之前；显式 null url → 字面量 "null"（非空）→ 走校验；
    // 空字符串 / 纯空白跳过校验；对象/数组型 url 在 ?.jsonPrimitive 处先抛。
    val url = obj["url"]?.jsonPrimitive?.content?.trim()?.take(500)
    if (!url.isNullOrBlank() && !isUrlAllowed(url)) return BotSetWebhookFieldsResult.InvalidUrl
    return BotSetWebhookFieldsResult.Ok(BotSetWebhookFields(url))
}
