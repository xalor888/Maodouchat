package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

// 必填请求体字段：空串或全空白直接回 400，调用方 `?: return@post` 保持早退。
internal suspend fun ApplicationCall.requireNonBlankValueOr400(value: String, missingMessage: String): String? {
    if (value.isBlank()) respond(HttpStatusCode.BadRequest, ErrorResponse(missingMessage))
    return value.takeIf { it.isNotBlank() }
}
