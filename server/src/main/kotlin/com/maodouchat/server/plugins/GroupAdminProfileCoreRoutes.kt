package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.GroupProfileRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 群管理路由：群资料（改名/公告/头像）。 */
internal fun Route.configureGroupAdminProfileCoreRoutes(
    userRepo: UserRepository,
    profileRepository: GroupProfileRepository,
    queryRepository: ConversationQueryRepository,
    participantRepository: ConversationParticipantRepository,
    avatarRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureGroupAdminProfileTextRoutes(
        userRepo = userRepo,
        profileRepository = profileRepository,
        queryRepository = queryRepository,
        participantRepository = participantRepository,
        json = json,
    )
    configureGroupAdminProfileAvatarRoutes(
        userRepo = userRepo,
        profileRepository = profileRepository,
        participantRepository = participantRepository,
        avatarRateLimiter = avatarRateLimiter,
    )
}
