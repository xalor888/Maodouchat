package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.GroupInvitationService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 群邀请生命周期（列表/接受/拒绝/撤销）：按域拆为查询/决策两簇的委托门面。 */
internal fun Route.configureGroupInvitationLifecycleRoutes(
    userRepo: UserRepository,
    invitationService: GroupInvitationService,
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
    pushService: FcmPushService,
    json: Json,
) {
    configureGroupInvitationQueryRoutes(
        invitationService = invitationService,
        participantRepository = participantRepository,
    )
    configureGroupInvitationDecisionRoutes(
        userRepo = userRepo,
        invitationService = invitationService,
        queryRepository = queryRepository,
        pushService = pushService,
        json = json,
    )
}
