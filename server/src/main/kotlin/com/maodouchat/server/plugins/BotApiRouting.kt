package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** Stable Bot API registry; route groups are implemented in focused plugins. */
/** Bot API application adapter. Compatibility-only probes and hint surfaces live separately. */
internal fun Route.configureBotApiRoutes(
    userRepo: UserRepository,
    starMessageRepo: StarMessageRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    serviceMessageRepo: ServiceMessageRepository,
    groupMembershipService: GroupMembershipService,
    groupProfileRepo: GroupProfileRepository,
    groupModerationRepo: GroupModerationRepository,
    groupInvitationService: GroupInvitationService,
    commandService: ConversationCommandService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    configureBotCoreRoutes(
        userRepo = userRepo,
        starMessageRepo = starMessageRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupMembershipService = groupMembershipService,
        groupProfileRepo = groupProfileRepo,
        groupModerationRepo = groupModerationRepo,
        groupInvitationService = groupInvitationService,
        commandService = commandService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )
}
