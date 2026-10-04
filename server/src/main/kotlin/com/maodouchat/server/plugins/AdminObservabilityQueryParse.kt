package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// /online、/audit-logs 的分页 limit：非法/缺省回默认，钳制到 1..maxLimit。
internal fun parseObservabilityLimit(params: Parameters, defaultLimit: Int = 100, maxLimit: Int = 500): Int =
    (params["limit"]?.toIntOrNull() ?: defaultLimit).coerceIn(1, maxLimit)

// /audit-logs 的 offset：非法/缺省回 0，负数按 0 处理（Long 口径）。
internal fun parseObservabilityOffsetLong(params: Parameters): Long =
    (params["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)

// /audit-logs/export 的 offset：Int 口径。
internal fun parseObservabilityOffsetInt(params: Parameters): Int =
    (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

// /audit-logs 的 action 筛选：trim 后为空即无筛选。
internal fun parseObservabilityAction(params: Parameters): String? =
    params["action"]?.trim()?.takeIf { it.isNotBlank() }

// /audit-logs 的搜索关键字：截断到 80 字符，空白即无筛选。
internal fun parseObservabilitySearch(params: Parameters): String? =
    params["q"]?.trim()?.take(80)?.takeIf { it.isNotBlank() }
