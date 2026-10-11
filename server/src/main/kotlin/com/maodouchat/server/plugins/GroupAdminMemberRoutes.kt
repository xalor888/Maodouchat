package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.GroupModerationRepository
import com.maodouchat.server.repository.GroupProfileRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 群管理路由：成员变更（移除/角色/转让/昵称/头衔/禁言/全员静音）。 */
internal fun Route.configureGroupAdminMemberRoutes(
    userRepo: UserRepository,
    membershipService: GroupMembershipService,
    profileRepository: GroupProfileRepository,
    moderationRepository: GroupModerationRepository,
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
    json: Json,
) {
    configureGroupAdminMembershipRoutes(
        userRepo = userRepo,
        membershipService = membershipService,
        profileRepository = profileRepository,
        queryRepository = queryRepository,
        participantRepository = participantRepository,
        json = json,
    )
    configureGroupAdminMuteRoutes(
        userRepo = userRepo,
        moderationRepository = moderationRepository,
        queryRepository = queryRepository,
        participantRepository = participantRepository,
        json = json,
    )
}
