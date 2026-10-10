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

/** Signal 密钥包上传簇：POST /api/keys/upload。 */
internal fun Route.configureSignalKeyUploadRoutes(
    signalKeyRepository: SignalKeyRepository,
) {

    post("/api/keys/upload") {
        val userId = call.requireUserId()
        val authSessionId = call.requireAuthSessionId()
        val request = call.receiveJson<UploadKeysRequest>()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
            return@post
        }
        if (!request.isValid()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("密钥包无效"))
            return@post
        }

        when (
            signalKeyRepository.uploadKeyPackage(
                userId = userId,
                authSessionId = authSessionId,
                deviceId = request.deviceId,
                identityKey = request.identityKey,
                registrationId = request.registrationId,
                signedPreKeyId = request.signedPreKeyId,
                signedPreKey = request.signedPreKey,
                signedPreKeySignature = request.signedPreKeySignature,
                preKeys = request.preKeys.map { PreKeyUpload(it.keyId, it.publicKey) },
                deviceName = request.deviceName,
            )
        ) {
            SignalKeyRepository.UploadKeyPackageResult.UPLOADED -> call.respondOk()
            SignalKeyRepository.UploadKeyPackageResult.DEVICE_ID_CONFLICT -> call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("设备编号冲突，请重新分配设备编号", code = "DEVICE_ID_CONFLICT"),
            )
            SignalKeyRepository.UploadKeyPackageResult.DEVICE_IDENTITY_MISMATCH -> call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("设备身份密钥与服务器记录不一致，请重新登录", code = "DEVICE_IDENTITY_MISMATCH"),
            )
            SignalKeyRepository.UploadKeyPackageResult.SESSION_CONFLICT -> call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("设备会话冲突，请重新登录", code = "DEVICE_SESSION_CONFLICT"),
            )
            SignalKeyRepository.UploadKeyPackageResult.INVALID_SIGNATURE -> call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("密钥包签名校验失败，请更新客户端重新生成密钥", code = "INVALID_KEY_SIGNATURE"),
            )
            SignalKeyRepository.UploadKeyPackageResult.INVALID_PRE_KEY -> call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("一次性预密钥无效", code = "INVALID_PRE_KEY"),
            )
        }
    }
}
