package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 探针标志·防泄漏与密钥。 */
internal fun Route.configureBotProbeFlagSecretLeakRoutes(
    botRateLimiter: BoundedRateLimiter,
) {
    BOT_PROBE_FLAG_SECRET_LEAK.forEach { probe ->
        get("/api/bot/${probe.path}") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@get
            BotRepository.logCommand(bot.id, null, null, probe.command)
            call.respond(buildJsonObject {
                put("ok", true)
                put("botId", bot.id)
                probe.flags.forEach { flag -> put(flag.name, flag.read()) }
                put("surface", probe.surface)
            })
        }
    }
}

private val BOT_PROBE_FLAG_SECRET_LEAK = listOf(
    BotFlagProbe("getSecretLeakFlags", "getSecretLeakFlags", 60, listOf(
        flag("secretCopyBlockEnabled", RuntimeConfigService::isSecretCopyBlockEnabled),
        flag("secretMediaExportBlockEnabled", RuntimeConfigService::isSecretMediaExportBlockEnabled),
        flag("screenshotDetectEnabled", RuntimeConfigService::isScreenshotDetectEnabled),
        flag("recentsExclusionEnabled", RuntimeConfigService::isRecentsExclusionEnabled),
    )),
    BotFlagProbe("getSecretVaultFlags", "getSecretVaultFlags", 61, listOf(
        flag("secretForwardBlockEnabled", RuntimeConfigService::isSecretForwardBlockEnabled),
        flag("secretChatExportBlockEnabled", RuntimeConfigService::isSecretChatExportBlockEnabled),
        flag("secretCopyBlockEnabled", RuntimeConfigService::isSecretCopyBlockEnabled),
        flag("secretMediaExportBlockEnabled", RuntimeConfigService::isSecretMediaExportBlockEnabled),
    )),
    BotFlagProbe("getSealedCryptoFlags", "getSealedCryptoFlags", 62, listOf(
        flag("sealedSenderEnabled", RuntimeConfigService::isSealedSenderEnabled),
        flag("pqxdhPreview", RuntimeConfigService::isPqxdhPreviewEnabled),
        flag("secretChatEnabled", RuntimeConfigService::isSecretChatEnabled),
    )),
)
