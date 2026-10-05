package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// 开发者分析天数（dashboard /analytics）：缺省/非法回 7，钳制到 1..90。
internal fun parseDeveloperAnalyticsDays(params: Parameters): Int =
    (params["days"]?.toIntOrNull() ?: 7).coerceIn(1, 90)

// 日志命令过滤（dashboard /logs）：trim 后为空回 null，下游按 null/字符串区分“不过滤/按命令过滤”。
internal fun parseDeveloperCommandFilter(params: Parameters): String? =
    params["command"]?.trim()?.takeIf { it.isNotBlank() }
