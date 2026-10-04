package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// "1" 字面量布尔开关（会话列表的 includeRevoked）：只有 "1" 算开，"true" 都不算。
internal fun parseQueryFlagOne(params: Parameters, name: String): Boolean =
    params[name] == "1"

// trim 后原样透出，缺省/空白回空串（/messages/search 的 q/chatId/userId 仓库层按空串=无筛选处理）。
internal fun parseTrimmedOrEmpty(params: Parameters, name: String): String =
    params[name]?.trim().orEmpty()

// Int 口径 offset（/messages/search 与 bot 命令日志都用 Int）：非法/缺省回 0，负数按 0 处理。
internal fun parseAdminListIntOffset(params: Parameters): Int =
    (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
