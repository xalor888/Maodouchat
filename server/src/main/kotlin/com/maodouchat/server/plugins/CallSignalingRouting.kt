package com.maodouchat.server.plugins

import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.CallSignalingService
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.TurnCredentialService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 通话信令路由门面：按域拆为 ICE 凭证 / 信令消息 / 待取信令三簇，零行为改动。 */
internal fun Route.configureCallSignalingRoutes(
    userRepository: UserRepository,
    callSignalingService: CallSignalingService,
    turnCredentialService: TurnCredentialService,
    pushService: FcmPushService,
    json: Json,
) {
    configureCallSignalingIceRoutes(turnCredentialService)
    configureCallSignalingMessageRoutes(
        userRepository = userRepository,
        callSignalingService = callSignalingService,
        pushService = pushService,
        json = json,
    )
    configureCallSignalingPendingRoutes(callSignalingService)
}
