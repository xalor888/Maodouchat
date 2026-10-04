package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// 导出/批量端点的 limit：非法/缺省回默认，钳制到 1..maxLimit（两类端点各用一套缺省/上限）。
internal fun parseExportLimit(params: Parameters, defaultLimit: Int, maxLimit: Int): Int =
    (params["limit"]?.toIntOrNull() ?: defaultLimit).coerceIn(1, maxLimit)
