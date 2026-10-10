package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.ConversationCommandService
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.GroupInvitationService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 会话生命周期：创建、邀请加入、退出/删除。 */
internal fun Route.configureConversationLifecycleRoutes(
    userRepo: UserRepository,
    commandService: ConversationCommandService,
    queryRepository: ConversationQueryRepository,
    invitationService: GroupInvitationService,
    pushService: FcmPushService,
    createRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureConversationCreateRoutes(
        userRepo = userRepo,
        commandService = commandService,
        queryRepository = queryRepository,
        invitationService = invitationService,
        pushService = pushService,
        createRateLimiter = createRateLimiter,
        json = json,
    )
    configureConversationJoinRoutes(
        userRepo = userRepo,
        queryRepository = queryRepository,
        invitationService = invitationService,
        json = json,
    )
    configureConversationLeaveRoutes(
        commandService = commandService,
        json = json,
    )
}
