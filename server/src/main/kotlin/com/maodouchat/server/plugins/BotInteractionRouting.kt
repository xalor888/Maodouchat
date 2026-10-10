package com.maodouchat.server.plugins

import com.maodouchat.server.service.ConversationCommandService
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.service.GroupMembershipService
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** Bot 交互（用户侧）子域路由门面：保留原签名，按域委托给簇。 */
internal fun Route.configureBotInteractionRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    commandService: ConversationCommandService,
    conversationQueryRepo: ConversationQueryRepository,
    groupMembershipService: GroupMembershipService,
    botCreateRateLimiter: BoundedRateLimiter,
    createChatRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    configureBotInteractionQueryRoutes(
        conversationParticipantRepo = conversationParticipantRepo,
    )

    configureBotInteractionDeliveryRoutes(
        userRepo = userRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        commandService = commandService,
        conversationQueryRepo = conversationQueryRepo,
        botCreateRateLimiter = botCreateRateLimiter,
        createChatRateLimiter = createChatRateLimiter,
    )

    configureBotInteractionManageRoutes(
        userRepo = userRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        groupMembershipService = groupMembershipService,
        json = json,
    )
}
