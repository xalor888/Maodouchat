package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupInvitationService
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

// 群管理三组路由的装配入口：Routing.kt 只留一处调用。
internal fun Route.configureGroupAdminRoutes(
    userRepo: UserRepository,
    groupMembershipService: GroupMembershipService,
    groupProfileRepo: GroupProfileRepository,
    groupModerationRepo: GroupModerationRepository,
    groupInvitationService: GroupInvitationService,
    conversationQueryRepo: ConversationQueryRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    avatarRateLimiter: BoundedRateLimiter,
    groupAuditRepo: GroupAuditRepository,
    signalKeyRepo: SignalKeyRepository,
    senderKeyDistributionRepo: SenderKeyDistributionRepository,
    json: Json,
) {
    configureGroupAdminMemberRoutes(
        userRepo = userRepo,
        membershipService = groupMembershipService,
        profileRepository = groupProfileRepo,
        moderationRepository = groupModerationRepo,
        queryRepository = conversationQueryRepo,
        participantRepository = conversationParticipantRepo,
        json = json,
    )
    configureGroupAdminProfileRoutes(
        userRepo = userRepo,
        profileRepository = groupProfileRepo,
        invitationService = groupInvitationService,
        queryRepository = conversationQueryRepo,
        participantRepository = conversationParticipantRepo,
        avatarRateLimiter = avatarRateLimiter,
        json = json,
    )
    configureGroupAdminAuditRoutes(
        participantRepository = conversationParticipantRepo,
        auditRepository = groupAuditRepo,
        signalKeyRepository = signalKeyRepo,
        senderKeyRepository = senderKeyDistributionRepo,
    )
}
