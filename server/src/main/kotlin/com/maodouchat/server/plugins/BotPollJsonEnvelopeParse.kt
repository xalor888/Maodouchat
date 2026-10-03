package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal fun parseBotPollJsonEnvelopeOrNull(body: String): JsonObject? {
    // 注意：runCatching 覆盖 parseToJsonElement + jsonObject 整个链，
    // 任意环节抛异常（坏 JSON、顶层非对象）都得 null（吞掉），
    // 处理器再把 null 报成 400 "invalid json"——逐字怪语义。
    return runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
}
