package com.maodouchat.server.plugins

import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.application.Application

/** Stable admin registry; route groups are implemented in focused plugins. */
internal fun Application.configureAdminRouting(
    userRepo: UserRepository,
    postRepo: PostRepository,
    services: RoutingServiceAssembly,
) {
    configureAdminManagementRouting(
        userRepo = userRepo,
        postRepo = postRepo,
        services = services,
    )
}
