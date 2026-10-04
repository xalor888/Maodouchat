package com.maodouchat.server.plugins

import io.ktor.http.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// /api/users 与 /api/users/search 的 q：trim 后截断到 100（底层 LIKE 全表扫描，超长关键字无意义）。
internal fun parseAccountSearchQuery(params: Parameters): String =
    params["q"]?.trim().orEmpty().take(100)

// 分页 limit：非法/缺省回 defaultLimit，钳制到 1..100。
internal fun parseAccountPageLimit(params: Parameters, defaultLimit: Int): Int =
    (params["limit"]?.toIntOrNull() ?: defaultLimit).coerceIn(1, 100)

// 分页 offset：非法/缺省回 0，负数按 0 处理。
internal fun parseAccountPageOffset(params: Parameters): Int =
    (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

// 附近的人 radiusKm：非法/缺省回 10.0，钳制到 0.5..30。
internal fun parseAccountNearbyRadiusKm(params: Parameters): Double =
    (params["radiusKm"]?.toDoubleOrNull() ?: 10.0).coerceIn(0.5, 30.0)

// PUT /api/users/me/username 的 username：缺键/显式 null → ""；
// 统一小写 + trim（8.40 起格式非法 400、已占用 409 分离，下游判定不动）。
internal fun parseAccountUsername(obj: JsonObject): String =
    obj["username"]?.jsonPrimitive?.content.orEmpty().trim().lowercase()
