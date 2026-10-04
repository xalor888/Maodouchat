package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 开发者面 bot 启停端点 "enabled" 抽取：布尔字面量或 "true"/"false" 字符串，
// 其余（含对象/数组型、坏 JSON、顶层非对象）一律 → null，下游报 400 "enabled required"。
internal fun parseDeveloperBotEnabled(body: String): Boolean? {
    return runCatching {
        val p = Json.parseToJsonElement(body).jsonObject["enabled"]?.jsonPrimitive
        p?.booleanOrNull ?: p?.content?.toBooleanStrictOrNull()
    }.getOrNull()
}
