package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 管理面用户 moderator 开关端点 "enabled" 抽取：布尔字面量或 "true"/"false" 字符串，
// 其余（含对象/数组型、坏 JSON、顶层非对象）一律 → null，下游报 400 "enabled bool required"。
internal fun parseAdminModeratorEnabled(body: String): Boolean? {
    return runCatching {
        val el = Json.parseToJsonElement(body).jsonObject["enabled"]?.jsonPrimitive
        el?.booleanOrNull ?: el?.content?.toBooleanStrictOrNull()
    }.getOrNull()
}
