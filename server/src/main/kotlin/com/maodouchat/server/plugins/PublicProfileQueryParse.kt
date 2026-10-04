package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// 公开资料用户名规范化：trim + 小写 + 去 @ 前缀，缺省回空串（下游按空白/长度做 400 判定）。
internal fun parseProfileUsername(params: Parameters, name: String): String =
    params[name]?.trim()?.lowercase()?.removePrefix("@").orEmpty()
