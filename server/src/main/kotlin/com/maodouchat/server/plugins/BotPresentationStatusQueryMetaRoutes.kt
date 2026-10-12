package com.maodouchat.server.plugins

import com.maodouchat.server.repository.logCommand
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 状态查询：服务器元信息（服务器时间 / Bot 身份；从 BotPresentationStatusQueryRoutes 按域拆出）。 */
internal fun Route.configureBotPresentationStatusQueryMetaRoutes(
    botRateLimiter: BoundedRateLimiter,
) {

get("/api/bot/getServerTime") {
    val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getServerTime")
    call.respond(
    buildJsonObject {
put("ok", true)
put("serverTime", System.currentTimeMillis())
    }
)
}

get("/api/bot/whoami") {
    val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
    com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "whoami")
    call.respond(
    buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("name", bot.name)
put("username", bot.username)
put("enabled", bot.enabled)
    }
)
}
}
