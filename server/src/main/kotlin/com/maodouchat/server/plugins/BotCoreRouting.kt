package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.ConversationCommandService
import com.maodouchat.server.service.GroupInvitationService
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** Bot 核心门面：发送 / 编辑 / 查询 3 簇（零行为改动；Bot API 用自有 token）。 */
internal fun Route.configureBotCoreRoutes(
    userRepo: UserRepository,
    starMessageRepo: StarMessageRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    serviceMessageRepo: ServiceMessageRepository,
    groupMembershipService: GroupMembershipService,
    groupProfileRepo: GroupProfileRepository,
    groupModerationRepo: GroupModerationRepository,
    groupInvitationService: GroupInvitationService,
    commandService: ConversationCommandService,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    // Bot API uses its own token and must not be nested under user JWT authentication.

    configureBotInfoRoutes(botSendRateLimiter, conversationParticipantRepo)

    configureBotCoreSendRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )

    configureBotCommandRoutes(botSendRateLimiter)

    configureBotChatActionRoutes(
        userRepo = userRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )

    configureBotWebhookRoutes(botSendRateLimiter)

    configureBotMemberRoutes(
        groupMembershipService = groupMembershipService,
        groupModerationRepo = groupModerationRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )

    configureBotCallbackRoutes(botSendRateLimiter)

    configureBotCoreEditRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotCoreChatRoutes(
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
    )

    configureBotChatAdminRoutes(
        groupProfileRepo = groupProfileRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )

    configureBotMessageForwardingRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotChatModerationRoutes(
        commandService = commandService,
        serviceMessageRepo = serviceMessageRepo,
        userRepo = userRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )
    configureBotProfileRoutes(
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
    )

    configureBotMemberPromotionRoutes(
        groupMembershipService = groupMembershipService,
        groupInvitationService = groupInvitationService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )

    configureBotChatInviteRoutes(
        userRepo = userRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupProfileRepo = groupProfileRepo,
        groupInvitationService = groupInvitationService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotGeoRoutes(
        userRepo = userRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupMembershipService = groupMembershipService,
        groupModerationRepo = groupModerationRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotMediaSendRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupMembershipService = groupMembershipService,
        groupModerationRepo = groupModerationRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotMediaGroupRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupMembershipService = groupMembershipService,
        groupModerationRepo = groupModerationRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotMediaProfileRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupMembershipService = groupMembershipService,
        groupModerationRepo = groupModerationRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotMessagingVariantsRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupMembershipService = groupMembershipService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotPollEditRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotReactionRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotChatMiscRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        starMessageRepo = starMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotPollQuizRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotPresentationRoutes(
        userRepository = userRepo,
        participantRepository = conversationParticipantRepo,
        serviceMessageRepository = serviceMessageRepo,
        botRateLimiter = botSendRateLimiter,
        json = json,
    )
}
