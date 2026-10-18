package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.service.ConversationCommandService
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.GroupInvitationService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

// 会话四组路由的装配入口：健康探活/会话设置/会话生命周期/会话查询四组，按名透传。
internal fun Route.configureConversationRoutes(
    userRepo: UserRepository,
    commandService: ConversationCommandService,
    participantRepository: ConversationParticipantRepository,
    queryRepository: ConversationQueryRepository,
    invitationService: GroupInvitationService,
    pushService: FcmPushService,
    createRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureHealthRoutes()
    configureConversationSettingsRoutes(
        userRepo = userRepo,
        commandService = commandService,
        participantRepository = participantRepository,
        json = json,
    )
    configureConversationLifecycleRoutes(
        userRepo = userRepo,
        commandService = commandService,
        queryRepository = queryRepository,
        invitationService = invitationService,
        pushService = pushService,
        createRateLimiter = createRateLimiter,
        json = json,
    )
    configureConversationQueryRoutes(
        queryRepository = queryRepository,
    )
}
