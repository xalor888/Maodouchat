package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.GroupInvitationService
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 群成员添加与群邀请审批门面：按域拆为成员添加 / 邀请生命周期两簇。 */
internal fun Route.configureGroupInvitationRoutes(
    userRepo: UserRepository,
    membershipService: GroupMembershipService,
    invitationService: GroupInvitationService,
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
    pushService: FcmPushService,
    json: Json,
) {
    authenticate("auth-jwt") {
        configureGroupInvitationMemberRoutes(
            userRepo, membershipService, invitationService,
            queryRepository, participantRepository, pushService, json,
        )
        configureGroupInvitationLifecycleRoutes(
            userRepo, invitationService,
            queryRepository, participantRepository, pushService, json,
        )
    }
}
