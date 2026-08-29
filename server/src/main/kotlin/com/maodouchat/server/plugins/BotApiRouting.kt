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
    groupMembershipRepo: GroupMembershipRepository,
    groupLifecycleService: GroupLifecycleService,
    groupProfileRepo: GroupProfileRepository,
    groupModerationRepo: GroupModerationRepository,
    groupInvitationRepo: GroupInvitationRepository,
    conversationLifecycleRepo: ConversationLifecycleRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureBotCoreRoutes(
        userRepo = userRepo,
        starMessageRepo = starMessageRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupMembershipRepo = groupMembershipRepo,
        groupLifecycleService = groupLifecycleService,
        groupProfileRepo = groupProfileRepo,
        groupModerationRepo = groupModerationRepo,
        groupInvitationRepo = groupInvitationRepo,
        conversationLifecycleRepo = conversationLifecycleRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )
}
