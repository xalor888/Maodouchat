package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.ReportWorkflow
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.SessionService
import com.maodouchat.server.messaging.v2.MessagingV2Repository
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 审核员：举报列表/改状态/执行处置——按读写域拆为两簇，本文件只做委托。 */
internal fun Route.configureModeratorReviewRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    reportRepo: ReportWorkflow,
    sessionService: SessionService,
    conversationParticipantRepo: ConversationParticipantRepository,
    json: Json,
    messagingV2Repository: MessagingV2Repository,
) {
    configureModeratorReviewQueryRoutes(userRepo, reportRepo)
    configureModeratorReviewManageRoutes(
        userRepo, postRepo, reportRepo, sessionService, conversationParticipantRepo, json, messagingV2Repository,
    )
}
