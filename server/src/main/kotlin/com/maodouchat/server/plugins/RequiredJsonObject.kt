package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.json.JsonObject

// 请求体必须是 JSON 对象：解析失败（坏 JSON 或顶层非对象）直接回 400，调用方 `?: return@post` 保持早退。
internal suspend fun ApplicationCall.requireJsonObjectOr400(body: String): JsonObject? {
    val obj = parseJsonObjectEnvelopeOrNull(body)
    if (obj == null) respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
    return obj
}
