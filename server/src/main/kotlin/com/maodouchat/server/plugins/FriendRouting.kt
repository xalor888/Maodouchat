package com.maodouchat.server.plugins

import com.maodouchat.server.repository.FriendRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.FcmPushService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** Authenticated friend graph routes with durable offline push and realtime wake-up. */
internal fun Route.configureFriendRoutes(
    userRepository: UserRepository,
    friendRepository: FriendRepository,
    pushService: FcmPushService,
    requestRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureFriendRequestRoutes(
        userRepository = userRepository,
        friendRepository = friendRepository,
        pushService = pushService,
        requestRateLimiter = requestRateLimiter,
        json = json,
    )
    configureFriendManageRoutes(
        friendRepository = friendRepository,
    )
}
