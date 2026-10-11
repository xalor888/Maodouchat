package com.maodouchat.server.plugins

import com.maodouchat.server.model.DeviceInfoResponse
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.SignalKeyRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// Signal 设备查询：设备列表（从 SignalKeyDeviceRoutes 按域拆出）。
internal fun Route.configureSignalKeyDeviceQueryRoutes(
    signalKeyRepository: SignalKeyRepository,
    conversationQueryRepository: ConversationQueryRepository,
    preKeyFetchLimiter: BoundedRateLimiter,
) {

    get("/api/keys/{userId}/devices") {
        val requesterId = call.requireUserId()
        val targetUserId = parseRawOrEmpty(call.parameters, "userId")
        if (!call.canFetchKeys(
                requesterId,
                targetUserId,
                conversationQueryRepository,
                preKeyFetchLimiter,
                allowSelf = true,
            )
        ) return@get
        val currentDeviceId = parseOptionalInt(call.request.queryParameters, "currentDeviceId")
        call.respond(
            signalKeyRepository.getDeviceInfos(
                targetUserId,
                currentDeviceId,
                includePending = requesterId == targetUserId,
            ).map {
                DeviceInfoResponse(
                    userId = it.userId,
                    deviceId = it.deviceId,
                    deviceName = it.deviceName,
                    identityKey = it.identityKey,
                    lastSeenAt = it.lastSeenAt,
                    isCurrent = it.isCurrent,
                    status = it.status,
                    confirmedAt = it.confirmedAt,
                    confirmedByDeviceId = it.confirmedByDeviceId,
                )
            },
        )
    }
}
