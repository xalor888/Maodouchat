package com.maodouchat.server.plugins

import com.maodouchat.server.repository.DeveloperAnalyticsRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.configureDeveloperPortalRoutes(
    developerAnalytics: DeveloperAnalyticsRepository,
) {
    // ─── Capability manifest ──────────────
    get("/capabilities") {
        // 8.131：manifest 与具体 bot 无关——dev_session 无需 bot id（此前一个 bot
        // 都没有的开发者取不到这份 bot 无关的能力清单）；bot token 仍可用
        if (!authenticateDeveloperIdentity(call)) return@get
        call.respond(buildCapabilityManifest())
    }

    // ─── Comprehensive health check ───────
    get("/health") {
        val bot = authenticateDeveloperBot(call) ?: return@get
        val health = developerAnalytics.health(bot.id)
        call.respond(health)
    }
}

// ─── Capability manifest ───────────────────────────

private fun buildCapabilityManifest(): CapabilityManifestResponse {
    return CapabilityManifestResponse(
        version = "1.0",
        messaging = MessagingCapabilities(
            canSendMessage = true,
            canSendImages = RuntimeConfigService.isImageSendEnabled(),
            canSendVideos = RuntimeConfigService.isVideoSendEnabled(),
            canSendFiles = RuntimeConfigService.isFileShareEnabled(),
            canSendVoice = RuntimeConfigService.isVoiceMessagesEnabled(),
            canSendMarkdown = RuntimeConfigService.isMarkdownEnabled(),
            maxMessageLength = 4_096,
            supportsReply = true,
            supportsForward = RuntimeConfigService.isMessageForwardingEnabled(),
            supportsPin = RuntimeConfigService.isMessagePinEnabled(),
            supportsEdit = RuntimeConfigService.isMessageEditEnabled(),
            supportsRevoke = RuntimeConfigService.isMessageRevokeEnabled(),
            supportsReaction = RuntimeConfigService.isReactionsEnabled()
        ),
        groups = GroupCapabilities(
            canJoinGroups = true,
            canCreateGroups = false,
            maxGroupSize = RuntimeConfigService.getInt(RuntimeConfigService.KEY_MAX_GROUP_SIZE, 200),
            canReadGroupHistory = true,
            canManageMembers = false,
            supportsGroupPlay = RuntimeConfigService.isGroupPlayEnabled(),
            supportsPolls = RuntimeConfigService.isPollsEnabled()
        ),
        ai = AiCapabilities(
            clientAiEnabled = RuntimeConfigService.isAiEnabled(),
            contentModerationEnabled = RuntimeConfigService.isAiContentModerationEnabled()
        ),
        integrations = IntegrationCapabilities(
            webhookSupported = true,
            webhookMaxRetries = 3,
            webhookTimeoutSeconds = 15,
            supportedUpdateTypes = listOf(
                "message", "callback_query", "inline_query",
                "command", "member_join", "member_leave"
            ),
            maxCommands = 100
        )
    )
}
