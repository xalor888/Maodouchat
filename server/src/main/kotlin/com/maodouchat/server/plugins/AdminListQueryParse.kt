package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// admin 列表端点（/users、/push-tokens）的分页 limit：非法/缺省回默认，钳制到 1..maxLimit。
internal fun parseAdminListLimit(params: Parameters, defaultLimit: Int = 50, maxLimit: Int = 200): Int =
    (params["limit"]?.toIntOrNull() ?: defaultLimit).coerceIn(1, maxLimit)

// offset：非法/缺省回 0，负数按 0 处理（Long 口径）。
internal fun parseAdminListOffset(params: Parameters): Long =
    (params["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)

// 搜索关键字：trim 后为空即无筛选，null 表示不筛选。
internal fun parseAdminListSearch(params: Parameters): String? =
    params["q"]?.trim()?.takeIf { it.isNotBlank() }

// 用户状态筛选：语义同搜索关键字。
internal fun parseAdminListStatus(params: Parameters): String? =
    params["status"]?.trim()?.takeIf { it.isNotBlank() }

// /bots 列表的分页：无钳制，非法/缺省回 50/0（沿用该端点原语义）。
internal fun parseAdminBotsListLimit(params: Parameters): Int =
    params["limit"]?.toIntOrNull() ?: 50

internal fun parseAdminBotsListOffset(params: Parameters): Int =
    params["offset"]?.toIntOrNull() ?: 0
