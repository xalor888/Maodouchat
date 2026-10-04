package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

// 必填聊天 ID：缺省或全空白直接回 400，调用方 `?: return@xxx` 保持早退。
internal suspend fun ApplicationCall.requireNonBlankParamOr400(name: String, missingMessage: String): String? {
    val value = parseNonBlankOrNull(parameters, name)
    if (value == null) respond(HttpStatusCode.BadRequest, ErrorResponse(missingMessage))
    return value
}
