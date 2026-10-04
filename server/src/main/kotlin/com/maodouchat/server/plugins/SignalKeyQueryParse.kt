package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// 可选 Int 查询参数（密钥路由的 currentDeviceId）：缺省/非法回 null，仓库层按“未指定”处理。
internal fun parseOptionalInt(params: Parameters, name: String): Int? =
    params[name]?.toIntOrNull()

// Int 查询参数带默认：缺省/非法回 default（sealed-sender 证书的 deviceId 缺省 1；负数原样透出）。
internal fun parseIntOrDefault(params: Parameters, name: String, default: Int): Int =
    params[name]?.toIntOrNull() ?: default
