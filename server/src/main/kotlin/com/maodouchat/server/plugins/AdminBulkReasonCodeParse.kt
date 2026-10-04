package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// bulk-ban 的 reasonCode：缺键/显式 null/空白一律回默认码；
// 非原语值在 ?.jsonPrimitive 处大声失败（路由层 StatusPages 映射为 400，不是 500）。
internal fun parseAdminBulkReasonCode(obj: JsonObject, defaultCode: String = "BULK_BAN"): String =
    obj["reasonCode"]?.jsonPrimitive?.content.orEmpty().ifBlank { defaultCode }
