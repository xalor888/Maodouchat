package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/**
 * Bot 管理（用户侧）子域路由：列出/创建机器人、token 轮换、webhook、删除、启停。
 * 从 configureRouting 抽出，仅做鉴权/DTO/校验/调用 BotRepository，不再在总路由内联。
 */
internal fun Route.configureBotManagementRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botCreateRateLimiter: BoundedRateLimiter,
    botTokenRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureBotManagementLifecycleRoutes(
        userRepo = userRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botCreateRateLimiter = botCreateRateLimiter,
        json = json,
    )
    configureBotManagementSettingsRoutes(
        userRepo = userRepo,
        botTokenRateLimiter = botTokenRateLimiter,
    )
}
