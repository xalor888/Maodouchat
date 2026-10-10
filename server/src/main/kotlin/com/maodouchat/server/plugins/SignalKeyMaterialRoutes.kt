package com.maodouchat.server.plugins

import com.maodouchat.server.model.ConfirmDeviceRequest
import com.maodouchat.server.model.DeviceInfoResponse
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateDeviceNameRequest
import com.maodouchat.server.model.UploadKeysRequest
import com.maodouchat.server.repository.ConfirmDeviceResult
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.DeleteDeviceResult
import com.maodouchat.server.repository.PreKeyUpload
import com.maodouchat.server.repository.SignalKeyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

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
