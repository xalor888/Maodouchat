package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.SignalKeyRepository
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route

/** Signal 密钥路由门面：保留原签名，按域委托给簇。 */
internal fun Route.configureSignalKeyMaterialRoutes(
    signalKeyRepository: SignalKeyRepository,
    conversationQueryRepository: ConversationQueryRepository,
    preKeyFetchLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {
        configureSignalKeyUploadRoutes(signalKeyRepository)
        configureSignalKeyFetchRoutes(
            signalKeyRepository = signalKeyRepository,
            conversationQueryRepository = conversationQueryRepository,
            preKeyFetchLimiter = preKeyFetchLimiter,
        )
        configureSignalKeyDeviceRoutes(
            signalKeyRepository = signalKeyRepository,
            conversationQueryRepository = conversationQueryRepository,
            preKeyFetchLimiter = preKeyFetchLimiter,
        )
    }
}
