package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** Bot 自身资料与行为类端点：按域拆为资料读、行为 2 簇（零行为改动）。 */
internal fun Route.configureBotMediaProfileRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    groupMembershipService: GroupMembershipService,
    groupModerationRepo: GroupModerationRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    configureBotMediaProfileReadRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupMembershipService = groupMembershipService,
        groupModerationRepo = groupModerationRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotMediaBehaviorRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupMembershipService = groupMembershipService,
        groupModerationRepo = groupModerationRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )
}
