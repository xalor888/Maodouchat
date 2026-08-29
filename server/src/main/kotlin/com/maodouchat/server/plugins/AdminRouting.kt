package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ModerationRuleRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.ReportRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.application.Application

/** Stable admin registry; route groups are implemented in focused plugins. */
fun Application.configureAdminRouting(
    userRepo: UserRepository,
    postRepo: PostRepository,
    moderationRuleRepo: ModerationRuleRepository,
    reportRepo: ReportRepository = ReportRepository(),
) {
    configureAdminManagementRouting(
        userRepo = userRepo,
        postRepo = postRepo,
        moderationRuleRepo = moderationRuleRepo,
        reportRepo = reportRepo,
    )
}
