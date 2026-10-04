package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// bulk 家族各端点的 "days" 抽取：缺键/解析失败默认 1 天，再按各端点自己的策略上下限收紧。
// 上下限由调用方用 DispositionService 的策略常量传入，硬编码天数会绕过处置上限（9.131 的教训）。
internal fun parseAdminBulkDays(obj: JsonObject, minDays: Int, maxDays: Int): Int =
    (obj["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1).coerceIn(minDays, maxDays)
