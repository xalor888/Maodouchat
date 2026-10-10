package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun Route.configureBotProbeBooleanRoutes(botRateLimiter: BoundedRateLimiter) {

    BOT_BOOLEAN_PROBES.forEach { probe ->
        get("/api/bot/${probe.path}") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            BotRepository.logCommand(bot.id, null, null, probe.command)
            call.respond(buildJsonObject {
                put("ok", true)
                put(probe.signal, true)
                put("botId", bot.id)
                put("surface", probe.surface)
                put("serverTime", System.currentTimeMillis())
            })
        }
    }
}

private data class BotBooleanProbe(
    val path: String,
    val command: String,
    val signal: String,
    val surface: Int,
)

private val BOT_BOOLEAN_PROBES = listOf(
    BotBooleanProbe("readyz", "readyz", "ready", 60),
    BotBooleanProbe("alivez", "alivez", "alive", 60),
    BotBooleanProbe("heartbeatz", "heartbeatz", "heartbeat", 60),
    BotBooleanProbe("pulsez", "pulsez", "pulse", 60),
    BotBooleanProbe("tickz", "tickz", "tick", 60),
    BotBooleanProbe("tockz", "tockz", "tock", 60),
    BotBooleanProbe("clangz", "clangz", "clang", 60),
    BotBooleanProbe("dingz", "dingz", "ding", 60),
)
