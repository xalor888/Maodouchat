package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 地理路由门面：按域拆为位置 / 查询 / 杂项 3 簇（零行为改动）。 */
internal fun Route.configureBotGeoRoutes(
    userRepo: UserRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    serviceMessageRepo: ServiceMessageRepository,
    groupMembershipService: GroupMembershipService,
    groupModerationRepo: GroupModerationRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    configureBotGeoLocationRoutes(
        userRepo = userRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        serviceMessageRepo = serviceMessageRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotGeoQueryRoutes(
        conversationParticipantRepo = conversationParticipantRepo,
        serviceMessageRepo = serviceMessageRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )

    configureBotGeoMiscRoutes(
        userRepo = userRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        serviceMessageRepo = serviceMessageRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )
}
