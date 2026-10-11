package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.SignalKeyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

// Signal 设备门面：保留原签名，按域委托给查询/管理 2 簇（零行为改动）。
// requireDeviceId 为设备域共享 helper（SignalKeyFetchRoutes 亦用），留在此文件。
internal fun Route.configureSignalKeyDeviceRoutes(
    signalKeyRepository: SignalKeyRepository,
    conversationQueryRepository: ConversationQueryRepository,
    preKeyFetchLimiter: BoundedRateLimiter,
) {
    configureSignalKeyDeviceQueryRoutes(
        signalKeyRepository = signalKeyRepository,
        conversationQueryRepository = conversationQueryRepository,
        preKeyFetchLimiter = preKeyFetchLimiter,
    )
    configureSignalKeyDeviceManageRoutes(
        signalKeyRepository = signalKeyRepository,
    )
}

internal suspend fun io.ktor.server.application.ApplicationCall.requireDeviceId(): Int? {
    val deviceId = parseOptionalInt(parameters, "deviceId")
    if (deviceId == null || deviceId !in 1..255) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("设备 ID 无效"))
        return null
    }
    return deviceId
}
