package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import com.maodouchat.server.service.GroupInvitationService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.*

/** Bot 成员解封与角色调整（限制/晋升拆为独立簇，本函数只做委托）。 */
internal fun Route.configureBotMemberPromotionRoutes(
    groupMembershipService: GroupMembershipService,
    groupModerationRepo: GroupModerationRepository,
    groupInvitationService: GroupInvitationService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureBotMemberRestrictRoutes(
        groupMembershipService = groupMembershipService,
        groupModerationRepo = groupModerationRepo,
        groupInvitationService = groupInvitationService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )
    configureBotMemberPromoteRoutes(
        groupMembershipService = groupMembershipService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )
}
