package com.maodouchat.server.plugins

import com.maodouchat.server.repository.FriendRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.FcmPushService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 好友请求：发送、查询、接受/拒绝/取消（按域拆为 3 簇的委托门面）。 */
internal fun Route.configureFriendRequestRoutes(
    userRepository: UserRepository,
    friendRepository: FriendRepository,
    pushService: FcmPushService,
    requestRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    configureFriendRequestSendRoutes(
        userRepository = userRepository,
        friendRepository = friendRepository,
        pushService = pushService,
        requestRateLimiter = requestRateLimiter,
        json = json,
    )
    configureFriendRequestQueryRoutes(friendRepository = friendRepository)
    configureFriendRequestDecisionRoutes(
        userRepository = userRepository,
        friendRepository = friendRepository,
        pushService = pushService,
        json = json,
    )
}
