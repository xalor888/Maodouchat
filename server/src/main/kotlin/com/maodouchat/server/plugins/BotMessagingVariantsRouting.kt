package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 消息变体门面：发送变体 / 成员 / 命令 3 簇（零行为改动）。 */
internal fun Route.configureBotMessagingVariantsRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    groupMembershipService: GroupMembershipService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    configureBotMessagingMemberRoutes(
        groupMembershipService = groupMembershipService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )

    configureBotMessagingSendVariantsRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotMessagingCommandRoutes(
        botSendRateLimiter = botSendRateLimiter,
    )
}
