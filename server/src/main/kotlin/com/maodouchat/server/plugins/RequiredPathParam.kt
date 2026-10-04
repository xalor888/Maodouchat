package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

// 必填路径/查询参数：缺失时直接回 400，调用方 `?: return@get` 保持早退。
internal suspend fun ApplicationCall.requirePathParamOr400(name: String, missingMessage: String): String? {
    val value = parameters[name]
    if (value == null) respond(HttpStatusCode.BadRequest, ErrorResponse(missingMessage))
    return value
}
