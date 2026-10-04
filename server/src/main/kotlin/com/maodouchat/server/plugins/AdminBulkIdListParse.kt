package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// 批量管理端点从 userIds/chatIds 抽取 ID 列表：数组逐元素取字面量（坏元素静默丢弃），
// 字符串按逗号/空白切分后去空串；逐 id 截断 64 字符、去重、按端点上限截断。
// 怪语义：缺键得空列表；显式 null 被当成字面量 "null" 保留（JsonNull.content == "null"）；
// 非数组非字符串（如对象）在切分分支大声失败，由路由层 StatusPages 映射为 400。
internal fun parseAdminBulkIdList(obj: JsonObject, key: String, limit: Int): List<String> {
    val raw = obj[key] ?: return emptyList()
    val values = when (raw) {
        is JsonArray -> raw.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
        else -> raw.jsonPrimitive.content.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotBlank() }
    }
    return values.map { it.take(64) }.distinct().take(limit)
}
