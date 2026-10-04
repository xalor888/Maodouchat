package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// 用户名修改端点请求体信封：坏 JSON、顶层非对象一律 → null，下游报 400 "参数无效"。
internal fun parseAccountJsonEnvelopeOrNull(body: String): JsonObject? {
    return runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
}
