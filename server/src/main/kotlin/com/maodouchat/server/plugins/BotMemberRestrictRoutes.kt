package com.maodouchat.server.plugins
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import com.maodouchat.server.service.GroupInvitationService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 群成员限制（禁言 / 踢出·解封按域拆为独立簇，本函数只做委托）。 */
internal fun Route.configureBotMemberRestrictRoutes(
    groupMembershipService: GroupMembershipService,
    groupModerationRepo: GroupModerationRepository,
    groupInvitationService: GroupInvitationService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureBotMemberRestrictMuteRoutes(
        groupModerationRepo = groupModerationRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )
    configureBotMemberRestrictBanRoutes(
        groupMembershipService = groupMembershipService,
        groupInvitationService = groupInvitationService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )
}
