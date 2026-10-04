package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// bulk 用户设置开关的严格布尔抽取：只认明确的真/假字面；缺字段或拼写错误返回 null，
// 由路由层报 400——隐私开关缺字段时绝不能静默默认 true（9.131）。
internal fun parseAdminBulkBooleanSetting(obj: JsonObject, key: String): Boolean? =
    when (obj[key]?.jsonPrimitive?.content?.lowercase()) {
        "false", "0", "no", "off" -> false
        "true", "1", "yes", "on" -> true
        else -> null
    }
