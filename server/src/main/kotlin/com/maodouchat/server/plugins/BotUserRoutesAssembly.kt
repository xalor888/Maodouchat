package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.service.ConversationCommandService
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.server.auth.*
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

// 用户侧 Bot 两组路由的装配入口：交互与管理两组都走 auth-jwt，鉴权块随装配整体下移，Routing.kt 只留一处调用。
internal fun Route.configureBotUserRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    commandService: ConversationCommandService,
    conversationQueryRepo: ConversationQueryRepository,
    groupMembershipService: GroupMembershipService,
    botCreateRateLimiter: BoundedRateLimiter,
    botTokenRateLimiter: BoundedRateLimiter,
    createChatRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    authenticate("auth-jwt") {
        configureBotInteractionRoutes(
            userRepo = userRepo,
            conversationParticipantRepo = conversationParticipantRepo,
            commandService = commandService,
            conversationQueryRepo = conversationQueryRepo,
            groupMembershipService = groupMembershipService,
            botCreateRateLimiter = botCreateRateLimiter,
            createChatRateLimiter = createChatRateLimiter,
            json = json,
        )
        configureBotManagementRoutes(
            userRepo = userRepo,
            conversationParticipantRepo = conversationParticipantRepo,
            botCreateRateLimiter = botCreateRateLimiter,
            botTokenRateLimiter = botTokenRateLimiter,
            json = json,
        )
    }
}
