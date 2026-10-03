package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * `POST /users/{id}/sessions/revoke` 与 `POST /broadcast` 请求体信封抽取：
 * 坏 JSON / 顶层非对象一律吞成 null，合法对象逐字原样返回。空 body 宽容
 * （`isBlank() -> null`）与 400 判定仍在处理器里，这里只换内联表达式。
 */
internal fun parseAdminManagementJsonEnvelopeOrNull(body: String): JsonObject? {
    // runCatching 覆盖整条 parse + jsonObject 链：任意环节抛都得 null。
    return runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
}
