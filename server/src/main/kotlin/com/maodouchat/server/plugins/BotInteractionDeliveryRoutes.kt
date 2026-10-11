package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.ConversationCommandService
import io.ktor.server.routing.Route


/** Bot 交互·投递：用户命令收件箱、DM 建联、回调按钮。 */
internal fun Route.configureBotInteractionDeliveryRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    commandService: ConversationCommandService,
    conversationQueryRepo: ConversationQueryRepository,
    botCreateRateLimiter: BoundedRateLimiter,
    createChatRateLimiter: BoundedRateLimiter,
) {
    configureBotInteractionInboxRoutes(
        userRepo = userRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botCreateRateLimiter = botCreateRateLimiter,
    )
    configureBotInteractionDmRoutes(
        userRepo = userRepo,
        conversationQueryRepo = conversationQueryRepo,
        commandService = commandService,
        createChatRateLimiter = createChatRateLimiter,
    )
    configureBotInteractionCallbackRoutes(
        userRepo = userRepo,
        conversationParticipantRepo = conversationParticipantRepo,
    )
}
