package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** Bot 群管理类端点：按域拆为邀请链接读、成员角色变更 2 簇（零行为改动）。 */
internal fun Route.configureBotMediaGroupRoutes(
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
    configureBotMediaGroupInviteRoutes(
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

    configureBotMediaGroupMemberRoleRoutes(
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
