package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ServiceMessageRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.ConversationCommandService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** Bot 会话生命周期与消息删除（leaveChat / deleteMessage，见簇文件）。 */
internal fun Route.configureBotChatModerationRoutes(
    commandService: ConversationCommandService,
    serviceMessageRepo: ServiceMessageRepository,
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    configureBotChatLeaveRoutes(
        commandService = commandService,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )
    configureBotChatDeleteMessageRoutes(
        serviceMessageRepo = serviceMessageRepo,
        userRepo = userRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )
}
