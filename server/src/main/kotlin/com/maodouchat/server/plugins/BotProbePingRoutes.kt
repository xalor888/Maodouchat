package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun Route.configureBotProbePingRoutes(botRateLimiter: BoundedRateLimiter) {

    BOT_PING_PROBES.forEach { probe ->
        get("/api/bot/${probe.path}") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            BotRepository.logCommand(bot.id, null, null, probe.command)
            call.respond(buildJsonObject {
                put("ok", true)
                put("botId", bot.id)
                put("surface", probe.surface)
                put("ping", probe.value)
            })
        }
    }
}

private data class BotPingProbe(
    val path: String,
    val command: String,
    val surface: Int,
    val value: String,
)

private val BOT_PING_PROBES = listOf(
    BotPingProbe("buzzz", "buzzz", 60, "buzz"),
    BotPingProbe("chimez", "chimez", 60, "chime"),
    BotPingProbe("ringz", "ringz", 60, "ring"),
    BotPingProbe("beepz", "beepz", 60, "beep"),
    BotPingProbe("pushz", "pushz", 60, "push"),
    BotPingProbe("quietz", "quietz", 60, "quiet"),
    BotPingProbe("fealz", "fealz", 60, "feel"),
    BotPingProbe("slidez", "slidez", 60, "slide"),
    BotPingProbe("leakz", "leakz", 60, "leak"),
    BotPingProbe("vaultz", "vaultz", 61, "vault"),
    BotPingProbe("sealz", "sealz", 62, "seal"),
    BotPingProbe("markz", "markz", 63, "mark"),
    BotPingProbe("linkz", "linkz", 64, "link"),
    BotPingProbe("privz", "privz", 65, "priv"),
    BotPingProbe("metaz", "metaz", 66, "meta"),
    BotPingProbe("typtz", "typtz", 67, "typing"),
    BotPingProbe("redz", "redz", 68, "read"),
    BotPingProbe("presz", "presz", 69, "presence"),
    BotPingProbe("lastsz", "lastsz", 70, "lastseen"),
)
