package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun parseManagementWebhookUrl(body: String): String? {
    // 注意：runCatching 覆盖 parseToJsonElement + jsonObject + 抽取整个链，
    // 任意环节抛异常（坏 JSON、顶层非对象、url 对象/数组型）都得 null（吞掉），
    // 与 BotSetWebhookParse 的大声失败不同——逐字怪语义。
    return runCatching {
        Json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.content
    }.getOrNull()?.trim()?.take(500)
}
