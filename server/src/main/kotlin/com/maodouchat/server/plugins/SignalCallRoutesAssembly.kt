package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import com.maodouchat.server.service.CallInviteRateLimiter
import com.maodouchat.server.service.CallSignalingService
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.TurnCredentialService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

// 加密通信三组路由的装配入口：Signal 密钥材料/密封发送者/通话信令三组，按名透传。
internal fun Route.configureSignalCallRoutes(
    userRepo: UserRepository,
    signalKeyRepository: SignalKeyRepository,
    conversationQueryRepository: ConversationQueryRepository,
    preKeyFetchLimiter: BoundedRateLimiter,
    signalingRepo: SignalingRepository,
    callInviteRateLimiter: CallInviteRateLimiter,
    turnCredentialService: TurnCredentialService,
    pushService: FcmPushService,
    json: Json,
) {
    configureSignalKeyMaterialRoutes(
        signalKeyRepository = signalKeyRepository,
        conversationQueryRepository = conversationQueryRepository,
        preKeyFetchLimiter = preKeyFetchLimiter,
    )
    configureSealedSenderRoutes()
    configureCallSignalingRoutes(
        userRepository = userRepo,
        callSignalingService = CallSignalingService(
            signalingRepo,
            userRepo,
            conversationQueryRepository,
            callInviteRateLimiter,
            turnCredentialService,
        ),
        turnCredentialService = turnCredentialService,
        pushService = pushService,
        json = json,
    )
}
