package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

// DTO 请求体：解析失败（空体/坏 JSON/类型错）直接回 400，调用方 `?: return@post` 保持早退。
// 文案按各端点原样透传，默认"参数无效"（24 处里 19 处本就如此）。
internal suspend inline fun <reified T> ApplicationCall.receiveJsonOr400(
    message: String = "参数无效",
    maxChars: Int = MAX_JSON_BODY_CHARS,
): T? {
    val body = receiveJson<T>(maxChars)
    if (body == null) respond(HttpStatusCode.BadRequest, ErrorResponse(message))
    return body
}
