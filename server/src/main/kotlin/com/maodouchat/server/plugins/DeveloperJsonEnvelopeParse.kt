package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 开发者面三端点（`POST /login`、`POST /bots`、`PUT /bots/{id}/commands`）
 * 请求体信封抽取：坏 JSON / 顶层非对象一律吞成 null，合法对象逐字原样返回。
 */
internal fun parseDeveloperJsonEnvelopeOrNull(body: String): JsonObject? {
    // runCatching 覆盖整条 parse + jsonObject 链：任意环节抛都得 null。
    return runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
}
