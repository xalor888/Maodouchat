package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 开发者账号·Bot 管理：创建/换 token/webhook/启停/删除。 */
internal fun Route.configureDeveloperAccountBotRoutes(
    devParticipantRepo: ConversationParticipantRepository,
    devJson: Json,
    developerBotCreateRateLimiter: BoundedRateLimiter,
    developerBotTokenRateLimiter: BoundedRateLimiter,
    developerBotSettingsRateLimiter: BoundedRateLimiter,
) {
    configureDeveloperAccountBotCreateRoutes(
        developerBotCreateRateLimiter = developerBotCreateRateLimiter,
        developerBotTokenRateLimiter = developerBotTokenRateLimiter,
    )
    configureDeveloperAccountBotManageRoutes(
        devParticipantRepo = devParticipantRepo,
        devJson = devJson,
        developerBotSettingsRateLimiter = developerBotSettingsRateLimiter,
    )
}
