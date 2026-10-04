package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 开发者面 webhook 更新端点 "url" 抽取：超 500 字符截断、缺席为 null，
// 坏 JSON / 顶层非对象一律吞成 null（与 BotManagementWebhookParse 同语义）。
internal fun parseDeveloperWebhookUrl(body: String): String? {
    return runCatching {
        Json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.content
    }.getOrNull()?.trim()?.take(500)
}
