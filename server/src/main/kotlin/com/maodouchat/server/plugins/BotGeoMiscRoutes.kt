package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.PinnedMessageRepository
import com.maodouchat.server.repository.ServiceMessageRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** Bot 地理·杂项：资料名片/联系人卡片/取消置顶。 */
internal fun Route.configureBotGeoMiscRoutes(
    userRepo: UserRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    serviceMessageRepo: ServiceMessageRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    configureBotGeoMiscProfileRoutes(botSendRateLimiter = botSendRateLimiter)
    configureBotGeoMiscContactRoutes(
        userRepo = userRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        serviceMessageRepo = serviceMessageRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )
    configureBotGeoMiscPinRoutes(
        userRepo = userRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )
}
