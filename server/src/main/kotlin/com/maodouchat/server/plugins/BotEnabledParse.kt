package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun parseManagementBotEnabled(body: String): Boolean? {
    // 注意：runCatching 覆盖 parseToJsonElement + jsonObject + 抽取整个链，
    // 任意环节抛异常（坏 JSON、顶层非对象、enabled 对象/数组型）都得 null（吞掉），
    // 处理器再把 null 报成 400 "enabled required"——逐字怪语义。
    return runCatching {
        val p = Json.parseToJsonElement(body).jsonObject["enabled"]?.jsonPrimitive
        p?.booleanOrNull ?: p?.content?.toBooleanStrictOrNull()
    }.getOrNull()
}
