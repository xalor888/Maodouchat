package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// bulk *-until 端点的时间戳抽取：按别名优先级取第一个可解析的毫秒时间戳，
// 缺键/全解析失败默认 0（0 即"无限制"，两端点原语义一致）。
// 别名的大声失败路径与旧内联 ?: 链逐字一致：某键存在但非 primitive 时
// 在 jsonPrimitive 处直接抛，不静默跳到下一个别名。
internal fun parseAdminBulkTimestampMs(obj: JsonObject, vararg keys: String): Long =
    keys.firstNotNullOfOrNull { key -> obj[key]?.jsonPrimitive?.content?.toLongOrNull() } ?: 0L
