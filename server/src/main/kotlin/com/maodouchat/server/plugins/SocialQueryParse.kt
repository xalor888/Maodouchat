package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// 动态时间线的 before 游标：原样透传，缺省/非法为 null 即从最新拉取。
internal fun parseSocialFeedBefore(params: Parameters): Long? =
    params["before"]?.toLongOrNull()

// 群玩法（签到榜/接龙/PK/投票同步）的 limit：无钳制，缺省/非法回默认，沿用原语义。
internal fun parseGroupPlayLimit(params: Parameters, defaultLimit: Int = 30): Int =
    params["limit"]?.toIntOrNull() ?: defaultLimit

// 好友请求列表（incoming/outgoing）的 status：两处内联都是缺键回 "PENDING"，空串原样透传。
internal fun parseFriendRequestStatus(params: Parameters): String =
    params["status"] ?: "PENDING"

// 审核事件列表的 needsReview：非法值回 null，按不过滤处理。
internal fun parseNeedsReview(params: Parameters): Boolean? =
    params["needsReview"]?.toBooleanStrictOrNull()
