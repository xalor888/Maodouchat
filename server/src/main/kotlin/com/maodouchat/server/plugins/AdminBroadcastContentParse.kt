package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class AdminBroadcastContent(val text: String, val title: String)

// POST /admin/broadcast 的 text/title 抽取：text 裁 2000 字符（空串与否由路由判 400），
// title 缺省或空白回退 "System"。显式 null 得字面量 "null"；对象/数组值大声失败。
internal fun parseAdminBroadcastContent(obj: JsonObject): AdminBroadcastContent {
    val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty().take(2000)
    val title = obj["title"]?.jsonPrimitive?.content?.trim()?.take(120).orEmpty().ifBlank { "System" }
    return AdminBroadcastContent(text, title)
}
