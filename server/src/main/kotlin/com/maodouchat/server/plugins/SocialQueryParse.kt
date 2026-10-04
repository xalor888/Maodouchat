package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// 动态时间线的 before 游标：原样透传，缺省/非法为 null 即从最新拉取。
internal fun parseSocialFeedBefore(params: Parameters): Long? =
    params["before"]?.toLongOrNull()

// 群玩法（签到榜/接龙/PK/投票同步）的 limit：无钳制，缺省/非法回默认，沿用原语义。
internal fun parseGroupPlayLimit(params: Parameters, defaultLimit: Int = 30): Int =
    params["limit"]?.toIntOrNull() ?: defaultLimit
