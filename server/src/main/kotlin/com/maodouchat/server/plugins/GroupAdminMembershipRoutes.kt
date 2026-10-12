package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.GroupProfileRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 群管理路由：成员变更（移除/角色/转让/昵称/头衔）。 */
internal fun Route.configureGroupAdminMembershipRoutes(
    userRepo: UserRepository,
    membershipService: GroupMembershipService,
    profileRepository: GroupProfileRepository,
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
    json: Json,
) {
    configureGroupAdminMembershipRemoveRoutes(
        userRepo = userRepo,
        membershipService = membershipService,
        json = json,
    )
    configureGroupAdminMembershipRoleRoutes(
        userRepo = userRepo,
        membershipService = membershipService,
        json = json,
    )
    configureGroupAdminMembershipNicknameRoutes(
        userRepo = userRepo,
        profileRepository = profileRepository,
        queryRepository = queryRepository,
        participantRepository = participantRepository,
        json = json,
    )
}
