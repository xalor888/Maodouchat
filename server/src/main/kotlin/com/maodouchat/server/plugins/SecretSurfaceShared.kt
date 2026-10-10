package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.BotRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond

/** 从 X-Bot-Token / Authorization Bearer 提取并校验 Bot，失败时已响应 401 并返回 null。 */
internal suspend fun authenticateBot(call: io.ktor.server.application.ApplicationCall): BotRepository.BotDto? {
    val headerToken = call.request.headers["X-Bot-Token"].orEmpty()
    val bearer = call.request.headers["Authorization"].bearerTokenOrNull().orEmpty()
    val token = headerToken.ifBlank { bearer }
    return BotRepository.authenticate(token)
        ?: run {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid bot token"))
            null
        }
}
