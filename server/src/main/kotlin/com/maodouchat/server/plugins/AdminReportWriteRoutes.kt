package com.maodouchat.server.plugins

import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.ReportWorkflow
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.SessionService
import io.ktor.server.routing.Route

/** 举报状态变更与处置。 */
internal fun Route.configureAdminReportWriteRoutes(
    reportRepo: ReportWorkflow,
    postRepo: PostRepository,
    userRepo: UserRepository,
    sessionService: SessionService,
) {
    configureAdminReportStatusRoutes(
        reportRepo = reportRepo,
    )
    configureAdminReportActionRoutes(
        reportRepo = reportRepo,
        postRepo = postRepo,
        userRepo = userRepo,
        sessionService = sessionService,
    )
}
