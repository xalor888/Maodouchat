package com.maodouchat.server.plugins

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 展示·健康探针：版本/存活/运行时间与状态探针（只读）。 */
internal fun Route.configureBotPresentationProbesHealthRoutes(
    botRateLimiter: BoundedRateLimiter,
) {

        get("/api/bot/getVersion") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getVersion")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("botId", bot.id)
    put("api", "maodouchat-bot")
    put("surface", 34)
    put("serverTime", System.currentTimeMillis())
            }
        )
        }

        get("/api/bot/healthz") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "healthz")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("botId", bot.id)
    put("status", "up")
    put("serverTime", System.currentTimeMillis())
            }
        )
        }

        get("/api/bot/uptime") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "uptime")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("botId", bot.id)
    put("serverTime", System.currentTimeMillis())
    put("surface", 39)
            }
        )
        }

        get("/api/bot/echoTime") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "echoTime")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("botId", bot.id)
    put("serverTime", System.currentTimeMillis())
    put("surface", 39)
            }
        )
        }

        get("/api/bot/versionz") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "versionz")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("botId", bot.id)
    put("surface", 60)
    put("serverTime", System.currentTimeMillis())
            }
        )
        }

        get("/api/bot/statusz") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "statusz")
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("status", "up")
    put("botId", bot.id)
    put("surface", 60)
    put("serverTime", System.currentTimeMillis())
            }
        )
        }
}
