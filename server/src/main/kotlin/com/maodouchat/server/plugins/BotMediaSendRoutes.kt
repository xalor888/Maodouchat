package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 媒体发送门面：按域拆为图片 / 音频文档 2 簇（零行为改动）。 */
internal fun Route.configureBotMediaSendRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    groupMembershipService: GroupMembershipService,
    groupModerationRepo: GroupModerationRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    configureBotMediaImageRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotMediaAudioDocRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )
}
