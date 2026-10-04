package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal sealed interface AdminRevokePrefixResult {
    data class Ok(val prefix: String) : AdminRevokePrefixResult
    data class Invalid(val message: String) : AdminRevokePrefixResult
}

internal sealed interface AdminRevokeAllResult {
    data class Ok(val revokeAll: Boolean) : AdminRevokeAllResult
    data class Invalid(val message: String) : AdminRevokeAllResult
}

// 会话撤销端点的 tokenHashPrefix：缺键 → ""；显式值必须是 JSON 字符串（trim 后用），
// 非字符串（对象/数组/数字/布尔/null）一律 Invalid，原先路由内直接 400。
internal fun parseAdminRevokePrefix(element: JsonElement?): AdminRevokePrefixResult {
    if (element == null) return AdminRevokePrefixResult.Ok("")
    val prefix = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        ?: return AdminRevokePrefixResult.Invalid("tokenHashPrefix must be a string")
    return AdminRevokePrefixResult.Ok(prefix)
}

// 会话撤销端点的 all 开关：缺键 → false；显式值必须是严格 JSON boolean——
// 字符串 "true"/数字等一律 Invalid（booleanOrNull 会宽松解析字符串，沿用原先的拒绝语义）。
internal fun parseAdminRevokeAll(element: JsonElement?): AdminRevokeAllResult {
    if (element == null) return AdminRevokeAllResult.Ok(false)
    val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        ?: return AdminRevokeAllResult.Invalid("all must be a boolean")
    return AdminRevokeAllResult.Ok(value)
}
