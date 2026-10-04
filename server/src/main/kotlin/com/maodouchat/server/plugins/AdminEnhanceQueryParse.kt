package com.maodouchat.server.plugins

import io.ktor.http.Parameters

// 设备一致性端点的可选 userId：trim 后为空即无筛选。
internal fun parseOptionalTrimmed(params: Parameters, name: String): String? =
    params[name]?.trim()?.takeIf { it.isNotBlank() }

// 审计导出 scope / 设备异常 status：trim 后转大写并截断，缺参回 null。
internal fun parseUpperToken(params: Parameters, name: String, maxLength: Int): String? =
    params[name]?.trim()?.uppercase()?.take(maxLength)

// 审计导出 fromMs/toMs：必填 Long，缺参或非法回 null，调用方按各自文案 400。
internal fun parseRequiredLong(params: Parameters, name: String): Long? =
    params[name]?.toLongOrNull()

// 限流仪表盘 range：缺省 24h；非法值回 null，调用方 400。
internal fun parseRateLimitRange(params: Parameters): String? {
    val range = params["range"]?.trim()?.lowercase() ?: return "24h"
    return range.takeIf { it == "1h" || it == "24h" || it == "7d" }
}
