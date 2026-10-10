package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** 举报与审核路由门面：按域拆为拉黑 / 举报 / 审核员 / 管理审核 4 簇（零行为改动）。 */
internal fun Route.configureReportModerationRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    reportRepo: ReportWorkflow,
    moderationRuleRepo: ModerationRuleRepository,
    authTokenRepo: AuthTokenRepository,
    pushTokenRepo: PushTokenRepository,
    sessionService: com.maodouchat.server.service.SessionService,
    conversationParticipantRepo: ConversationParticipantRepository,
    reportRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    configureReportUserBlockRoutes(
        userRepo = userRepo,
    )

    configureReportUserReportRoutes(
        reportRepo = reportRepo,
        reportRateLimiter = reportRateLimiter,
    )

    configureModeratorReviewRoutes(
        userRepo = userRepo,
        postRepo = postRepo,
        reportRepo = reportRepo,
        sessionService = sessionService,
        conversationParticipantRepo = conversationParticipantRepo,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureAdminReportModerationRoutes(
        userRepo = userRepo,
        moderationRuleRepo = moderationRuleRepo,
    )
}
