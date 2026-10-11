package com.maodouchat.server.plugins

import com.maodouchat.server.model.ConfirmDeviceRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateDeviceNameRequest
import com.maodouchat.server.repository.ConfirmDeviceResult
import com.maodouchat.server.repository.DeleteDeviceResult
import com.maodouchat.server.repository.SignalKeyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put

// Signal 设备管理：改名/确认/删除（从 SignalKeyDeviceRoutes 按域拆出）。
internal fun Route.configureSignalKeyDeviceManageRoutes(
    signalKeyRepository: SignalKeyRepository,
) {

    put("/api/keys/devices/{deviceId}/name") {
        val requesterId = call.requireUserId()
        val deviceId = call.requireDeviceId() ?: return@put
        val request = call.receiveJsonOr400<UpdateDeviceNameRequest>() ?: return@put
        val name = request.deviceName.trim()
        if (name.isEmpty() || name.length > 50) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("设备名长度需 1-50 字符"))
            return@put
        }
        if (!signalKeyRepository.updateDeviceName(requesterId, deviceId, name)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("设备不存在"))
            return@put
        }
        call.respondOk()
    }

    post("/api/keys/devices/{deviceId}/confirm") {
        val requesterId = call.requireUserId()
        val deviceId = call.requireDeviceId() ?: return@post
        val request = call.receiveJsonOr400<ConfirmDeviceRequest>() ?: return@post
        when (signalKeyRepository.confirmDevice(requesterId, deviceId, request.approverDeviceId, request.signature)) {
            ConfirmDeviceResult.CONFIRMED,
            ConfirmDeviceResult.ALREADY_CONFIRMED -> call.respondOk()
            ConfirmDeviceResult.NOT_FOUND ->
                call.respond(HttpStatusCode.NotFound, ErrorResponse("设备不存在"))
            ConfirmDeviceResult.APPROVER_NOT_TRUSTED ->
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("请使用已确认的其他设备批准登录"))
            ConfirmDeviceResult.INVALID_PROOF ->
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("设备批准证明无效"))
            ConfirmDeviceResult.INVALID ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("设备 ID 无效"))
        }
    }

    delete("/api/keys/devices/{deviceId}") {
        val requesterId = call.requireUserId()
        val deviceId = call.requireDeviceId() ?: return@delete
        val authSessionId = call.optionalAuthSessionId()
        val currentDeviceId = authSessionId?.let { sessionId ->
            signalKeyRepository.getDeviceIdForAuthSession(sessionId)
        }
        if (currentDeviceId == deviceId) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能移除当前登录设备"))
            return@delete
        }
        val removal = signalKeyRepository.deleteDeviceAndRevokeSessionsGuarded(requesterId, deviceId)
        when (removal.result) {
            DeleteDeviceResult.NOT_FOUND ->
                call.respond(HttpStatusCode.NotFound, ErrorResponse("设备不存在"))
            DeleteDeviceResult.LAST_CONFIRMED ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("至少保留一个已确认设备"))
            DeleteDeviceResult.DELETED -> {
                disconnectUserSessionsByAuthSessionIds(
                    requesterId,
                    removal.revokedSessionIds,
                    "该登录设备已被移除",
                )
                call.respondOk()
            }
        }
    }
}
