package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.service.ConversationCommandService
import com.maodouchat.server.service.GroupInvitationService
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

// Bot 四组路由的装配入口：探针/运行旗标只需限流器，其余按名透传。
internal fun Route.configureBotRoutes(
    userRepo: UserRepository,
    starMessageRepo: StarMessageRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    serviceMessageRepo: ServiceMessageRepository,
    groupMembershipService: GroupMembershipService,
    groupProfileRepo: GroupProfileRepository,
    groupModerationRepo: GroupModerationRepository,
    groupInvitationService: GroupInvitationService,
    conversationCommandService: ConversationCommandService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    configureBotProbeRoutes(botSendRateLimiter)
    configureBotRuntimeFlagRoutes(botSendRateLimiter)
    configureBotHintRoutes(
        userRepository = userRepo,
        participantRepository = conversationParticipantRepo,
        serviceMessageRepository = serviceMessageRepo,
        botRateLimiter = botSendRateLimiter,
        json = json,
    )
    configureBotApiRoutes(
        userRepo = userRepo,
        starMessageRepo = starMessageRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupMembershipService = groupMembershipService,
        groupProfileRepo = groupProfileRepo,
        groupModerationRepo = groupModerationRepo,
        groupInvitationService = groupInvitationService,
        commandService = conversationCommandService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )
}
