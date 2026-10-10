package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun Route.configureSecretSurfaceHealthzRoutes() {
    // ── 8 个新 surface 的 healthz（burnz/ttlz/fwlz/simz/2faz/ndz/dvz/sntz）──
    get("/api/bot/burnz") { surfaceHealth(call, "burnz", 71) }
    get("/api/bot/ttlz") { surfaceHealth(call, "ttlz", 72) }
    get("/api/bot/fwlz") { surfaceHealth(call, "fwlz", 73) }
    get("/api/bot/simz") { surfaceHealth(call, "simz", 74) }
    get("/api/bot/2faz") { surfaceHealth(call, "2faz", 75) }
    get("/api/bot/ndz") { surfaceHealth(call, "ndz", 76) }
    get("/api/bot/dvz") { surfaceHealth(call, "dvz", 77) }
    get("/api/bot/sntz") { surfaceHealth(call, "sntz", 78) }
}

/** 单个 surface 的 healthz：鉴权 + 记命令 + 返回 ok/surface/ping。鉴权失败时已响应 401 并返回 null。 */
private suspend fun surfaceHealth(
    call: io.ktor.server.application.ApplicationCall,
    name: String,
    surface: Int
) {
    val bot = authenticateBot(call) ?: return
    BotRepository.logCommand(bot.id, null, null, name)
    call.respond(
                buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("surface", surface)
put("ping", name)
                }
            )
}
