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

/** Signal 密钥拉取簇：预密钥包/设备密钥包查询。 */
internal fun Route.configureSignalKeyFetchRoutes(
    signalKeyRepository: SignalKeyRepository,
    conversationQueryRepository: ConversationQueryRepository,
    preKeyFetchLimiter: BoundedRateLimiter,
) {

    get("/api/keys/{userId}/prekey-bundle") {
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
        val deviceId = signalKeyRepository.getDeviceIds(targetUserId, confirmedOnly = true).firstOrNull()
        if (deviceId == null || !signalKeyRepository.isDeviceConfirmed(targetUserId, deviceId)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("用户密钥未上传或设备未确认"))
            return@get
        }
        val bundle = signalKeyRepository.getBundle(targetUserId, deviceId)
        if (bundle == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("用户密钥未上传"))
            return@get
        }
        call.respond(bundle.toPreKeyBundleResponse())
    }

    get("/api/keys/{userId}/devices/{deviceId}/prekey-bundle") {
        val requesterId = call.requireUserId()
        val targetUserId = parseRawOrEmpty(call.parameters, "userId")
        val deviceId = call.requireDeviceId() ?: return@get
        if (!call.canFetchKeys(
                requesterId,
                targetUserId,
                conversationQueryRepository,
                preKeyFetchLimiter,
                allowSelf = true,
            )
        ) return@get
        if (!signalKeyRepository.isDeviceConfirmed(targetUserId, deviceId)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("设备尚未确认"))
            return@get
        }
        val bundle = signalKeyRepository.getBundle(targetUserId, deviceId)
        if (bundle == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("设备密钥未上传"))
            return@get
        }
        call.respond(bundle.toDevicePreKeyBundleResponse())
    }

    get("/api/keys/{userId}/prekey-bundles") {
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
        val bundles = signalKeyRepository.getDeviceIds(targetUserId).mapNotNull {
            signalKeyRepository.getBundle(
                targetUserId,
                it,
                consumeOneTimePreKey = false,
                includeOneTimePreKey = false,
            )
        }
        if (bundles.isEmpty()) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("用户密钥未上传"))
            return@get
        }
        call.respond(bundles.map { it.toDevicePreKeyBundleResponse() })
    }
}
