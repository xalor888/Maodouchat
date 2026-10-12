package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 开发者账号·Bot 管理：webhook/启停/删除。 */
internal fun Route.configureDeveloperAccountBotManageRoutes(
    devParticipantRepo: ConversationParticipantRepository,
    devJson: Json,
    developerBotSettingsRateLimiter: BoundedRateLimiter,
)
{
    configureDeveloperAccountBotWebhookRoutes(
        developerBotSettingsRateLimiter = developerBotSettingsRateLimiter,
    )
    configureDeveloperAccountBotLifecycleRoutes(
        devParticipantRepo = devParticipantRepo,
        devJson = devJson,
        developerBotSettingsRateLimiter = developerBotSettingsRateLimiter,
    )
}
