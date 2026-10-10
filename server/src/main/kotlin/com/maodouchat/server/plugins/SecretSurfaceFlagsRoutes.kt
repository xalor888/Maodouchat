package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun Route.configureSecretSurfaceFlagsRoutes() {

    // ── flags 集合（一次取回全部 8 个门）──
    get("/api/bot/getSecretSurfaceFlags") {
        val bot = authenticateBot(call) ?: return@get
        BotRepository.logCommand(bot.id, null, null, "getSecretSurfaceFlags")
        call.respond(
            buildJsonObject {
                put("ok", true)
                put("botId", bot.id)
                secretSurfaceBotFlags().forEach { (k, v) -> put(k, v) }
                put("surface", 71)
            }
        )
    }
}

/** 8 个新 surface 的 Bot 能力门字段（对应既有 getXxxFlags 返回体风格）。 */
private fun secretSurfaceBotFlags(): Map<String, Boolean> = mapOf(
    "secretScreenshotBurnEnabled" to RuntimeConfigService.isSecretScreenshotBurnEnabled(),
    "secretAutoDestroyEnabled" to RuntimeConfigService.isSecretAutoDestroyEnabled(),
    "secretForwardWhitelistEnabled" to RuntimeConfigService.isSecretForwardWhitelistEnabled(),
    "secretSimChangeProtectionEnabled" to RuntimeConfigService.isSecretSimChangeProtectionEnabled(),
    "secret2faGateEnabled" to RuntimeConfigService.isSecret2faGateEnabled(),
    "secretNewDeviceRiskEnabled" to RuntimeConfigService.isSecretNewDeviceRiskEnabled(),
    "secretDeviceVerifyEnabled" to RuntimeConfigService.isSecretDeviceVerifyEnabled(),
    "secretSessionNoticeEnabled" to RuntimeConfigService.isSecretSessionNoticeEnabled()
)
