package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// PUT /admin/settings 的 settings 抽取：有 "settings" 键就取它的对象（非对象值大声失败，
// StatusPages 映射 400），没有就把整个顶层对象当 settings；值逐个取字面量，对象/数组值静默丢掉。
internal fun parseAdminSettingsUpdates(obj: JsonObject): Map<String, String> {
    val settingsObj = obj["settings"]?.jsonObject ?: obj
    return settingsObj.mapNotNull { (key, value) ->
        val s = runCatching { value.jsonPrimitive.content }.getOrNull() ?: return@mapNotNull null
        key to s
    }.toMap()
}
