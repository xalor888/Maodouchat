package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.logCommand
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.isBlindWatermarkEnabled
import com.maodouchat.server.service.isLinkPreviewEnabled
import com.maodouchat.server.service.isMessageStarringEnabled
import com.maodouchat.server.service.isNotificationPreviewEnabled
import com.maodouchat.server.service.isPresenceEnabled
import com.maodouchat.server.service.isReactionsEnabled
import com.maodouchat.server.service.isReadReceiptsEnabled
import com.maodouchat.server.service.isSecretAutoDisappearEnabled
import com.maodouchat.server.service.isSecretExternalLinkBlockEnabled
import com.maodouchat.server.service.isSecretLastSeenBlockEnabled
import com.maodouchat.server.service.isSecretLinkPreviewBlockEnabled
import com.maodouchat.server.service.isSecretListPreviewBlockEnabled
import com.maodouchat.server.service.isSecretNotifPreviewBlockEnabled
import com.maodouchat.server.service.isSecretPresenceBlockEnabled
import com.maodouchat.server.service.isSecretReactionBlockEnabled
import com.maodouchat.server.service.isSecretReadReceiptBlockEnabled
import com.maodouchat.server.service.isSecretStarBlockEnabled
import com.maodouchat.server.service.isSecretTypingBlockEnabled
import com.maodouchat.server.service.isTypingIndicatorsEnabled
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bot 探针标志·隐私元数据。 */
internal fun Route.configureBotProbeFlagSecretPrivacyRoutes(
    botRateLimiter: BoundedRateLimiter,
) {
    BOT_PROBE_FLAG_SECRET_PRIVACY.forEach { probe ->
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

private val BOT_PROBE_FLAG_SECRET_PRIVACY = listOf(
    BotFlagProbe("getMarkPrivacyFlags", "getMarkPrivacyFlags", 63, listOf(
        flag("secretAutoDisappearEnabled", { RuntimeConfigService.isSecretAutoDisappearEnabled() }),
        flag("blindWatermarkEnabled", { RuntimeConfigService.isBlindWatermarkEnabled() }),
    )),
    BotFlagProbe("getLinkPrivacyFlags", "getLinkPrivacyFlags", 64, listOf(
        flag("secretLinkPreviewBlockEnabled", { RuntimeConfigService.isSecretLinkPreviewBlockEnabled() }),
        flag("secretExternalLinkBlockEnabled", { RuntimeConfigService.isSecretExternalLinkBlockEnabled() }),
        flag("linkPreviewEnabled", { RuntimeConfigService.isLinkPreviewEnabled() }),
    )),
    BotFlagProbe("getNotifyPrivacyFlags", "getNotifyPrivacyFlags", 65, listOf(
        flag("secretNotifPreviewBlockEnabled", { RuntimeConfigService.isSecretNotifPreviewBlockEnabled() }),
        flag("secretListPreviewBlockEnabled", { RuntimeConfigService.isSecretListPreviewBlockEnabled() }),
        flag("secretReactionBlockEnabled", { RuntimeConfigService.isSecretReactionBlockEnabled() }),
        flag("secretStarBlockEnabled", { RuntimeConfigService.isSecretStarBlockEnabled() }),
        flag("notificationPreviewEnabled", { RuntimeConfigService.isNotificationPreviewEnabled() }),
    )),
    BotFlagProbe("getSecretMetaFlags", "getSecretMetaFlags", 66, listOf(
        flag("secretReactionBlockEnabled", { RuntimeConfigService.isSecretReactionBlockEnabled() }),
        flag("secretStarBlockEnabled", { RuntimeConfigService.isSecretStarBlockEnabled() }),
        flag("reactionsEnabled", { RuntimeConfigService.isReactionsEnabled() }),
        flag("messageStarringEnabled", { RuntimeConfigService.isMessageStarringEnabled() }),
    )),
    BotFlagProbe("getSecretTypingFlags", "getSecretTypingFlags", 67, listOf(
        flag("secretTypingBlockEnabled", { RuntimeConfigService.isSecretTypingBlockEnabled() }),
        flag("typingIndicatorsEnabled", { RuntimeConfigService.isTypingIndicatorsEnabled() }),
    )),
    BotFlagProbe("getSecretReadReceiptFlags", "getSecretReadReceiptFlags", 68, listOf(
        flag("secretReadReceiptBlockEnabled", { RuntimeConfigService.isSecretReadReceiptBlockEnabled() }),
        flag("readReceiptsEnabled", { RuntimeConfigService.isReadReceiptsEnabled() }),
    )),
    BotFlagProbe("getSecretPresenceFlags", "getSecretPresenceFlags", 69, listOf(
        flag("secretPresenceBlockEnabled", { RuntimeConfigService.isSecretPresenceBlockEnabled() }),
        flag("presenceEnabled", { RuntimeConfigService.isPresenceEnabled() }),
    )),
    BotFlagProbe("getSecretLastSeenFlags", "getSecretLastSeenFlags", 70, listOf(
        flag("secretLastSeenBlockEnabled", { RuntimeConfigService.isSecretLastSeenBlockEnabled() }),
        flag("presenceEnabled", { RuntimeConfigService.isPresenceEnabled() }),
    )),
)
