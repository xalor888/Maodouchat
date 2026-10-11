package com.maodouchat.server.plugins
import com.maodouchat.server.model.ChatType
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.service.GroupInvitationService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.maodouchat.server.model.ErrorResponse

/** Bot 群邀请链接（导出 / 撤销按域拆为独立簇，本函数只做委托）。 */
internal fun Route.configureBotChatInviteLinkRoutes(
    groupInvitationService: GroupInvitationService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureBotChatInviteLinkExportRoutes(
        groupInvitationService = groupInvitationService,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
    )
    configureBotChatInviteLinkRevokeRoutes(
        groupInvitationService = groupInvitationService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )
}
