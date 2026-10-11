package com.maodouchat.server.plugins

import com.maodouchat.server.messaging.v2.MessagingV2Repository
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.ReportWorkflow
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.SessionService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 审核员：举报改状态 / 执行处置。 */
internal fun Route.configureModeratorReviewManageRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    reportRepo: ReportWorkflow,
    sessionService: SessionService,
    conversationParticipantRepo: ConversationParticipantRepository,
    json: Json,
    messagingV2Repository: MessagingV2Repository,
) {
    configureModeratorReviewStatusRoutes(
        userRepo = userRepo,
        reportRepo = reportRepo,
    )
    configureModeratorReviewActionRoutes(
        userRepo = userRepo,
        postRepo = postRepo,
        reportRepo = reportRepo,
        sessionService = sessionService,
        conversationParticipantRepo = conversationParticipantRepo,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )
}
