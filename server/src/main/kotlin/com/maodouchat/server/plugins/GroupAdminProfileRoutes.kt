package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.GroupProfileRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.GroupInvitationService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 群管理路由：群资料（改名/公告/邀请链接/头像）。 */
internal fun Route.configureGroupAdminProfileRoutes(
    userRepo: UserRepository,
    profileRepository: GroupProfileRepository,
    invitationService: GroupInvitationService,
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
    avatarRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureGroupAdminProfileCoreRoutes(
        userRepo = userRepo,
        profileRepository = profileRepository,
        queryRepository = queryRepository,
        participantRepository = participantRepository,
        avatarRateLimiter = avatarRateLimiter,
        json = json,
    )
    configureGroupAdminInviteTokenRoutes(
        userRepo = userRepo,
        invitationService = invitationService,
        queryRepository = queryRepository,
        participantRepository = participantRepository,
    )
}
