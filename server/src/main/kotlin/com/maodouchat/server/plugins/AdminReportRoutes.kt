package com.maodouchat.server.plugins

import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.ReportWorkflow
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.routing.Route


/** 举报管理（admin-jwt 代理）。 */
internal fun Route.configureAdminReportRoutes(
    reportRepo: ReportWorkflow,
    postRepo: PostRepository,
    userRepo: UserRepository,
    sessionService: com.maodouchat.server.service.SessionService,
) {
    configureAdminReportQueryRoutes(
        reportRepo = reportRepo,
    )
    configureAdminReportWriteRoutes(
        reportRepo = reportRepo,
        postRepo = postRepo,
        userRepo = userRepo,
        sessionService = sessionService,
    )
}
