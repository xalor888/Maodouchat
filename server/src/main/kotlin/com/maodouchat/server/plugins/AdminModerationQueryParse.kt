package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// 举报列表的 status 筛选：trim 后空白或哨兵值 ALL 都等于不筛选。
internal fun parseModerationStatusFilter(params: Parameters): String? =
    parseOptionalTrimmed(params, "status")?.takeIf { it != "ALL" }

// 查询串里的布尔开关：只有字面量 "true" 算开，其余一律关（风控事件的 pending）。
internal fun parseQueryFlag(params: Parameters, name: String): Boolean =
    params[name] == "true"
