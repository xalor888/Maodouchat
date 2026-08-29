package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.model.ConfirmDeviceRequest
import com.maodouchat.server.model.DeviceInfoResponse
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateDeviceNameRequest
import com.maodouchat.server.model.UploadKeysRequest
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.SignalKeyRepository
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.SealedSenderCertificateService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Authenticated Signal device-key lifecycle and sealed-sender certificate adapter. */
internal fun Route.configureSignalKeyRoutes(
    signalKeyRepository: SignalKeyRepository,
    conversationQueryRepository: ConversationQueryRepository,
    preKeyFetchLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {
        post("/api/keys/upload") {
            val principal = call.principal<JWTPrincipal>()!!
            val userId = principal.payload.subject
            val authSessionId = JwtConfig.authSessionId(principal.payload)!!
            val request = call.receiveBoundedText()?.let { parseJson<UploadKeysRequest>(it) }
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
                    preKeys = request.preKeys.map { SignalKeyRepository.PreKeyUpload(it.keyId, it.publicKey) },
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

        get("/api/keys/{userId}/prekey-bundle") {
            val requesterId = call.principal<JWTPrincipal>()!!.payload.subject
            val targetUserId = call.parameters["userId"].orEmpty()
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

        get("/api/keys/{userId}/devices") {
            val requesterId = call.principal<JWTPrincipal>()!!.payload.subject
            val targetUserId = call.parameters["userId"].orEmpty()
            if (!call.canFetchKeys(
                    requesterId,
                    targetUserId,
                    conversationQueryRepository,
                    preKeyFetchLimiter,
                    allowSelf = true,
                )
            ) return@get
            val currentDeviceId = call.request.queryParameters["currentDeviceId"]?.toIntOrNull()
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

        put("/api/keys/devices/{deviceId}/name") {
            val requesterId = call.principal<JWTPrincipal>()!!.payload.subject
            val deviceId = call.requireDeviceId() ?: return@put
            val request = call.receiveBoundedText()?.let { parseJson<UpdateDeviceNameRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@put
            }
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
            val requesterId = call.principal<JWTPrincipal>()!!.payload.subject
            val deviceId = call.requireDeviceId() ?: return@post
            val request = call.receiveBoundedText()?.let { parseJson<ConfirmDeviceRequest>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
            when (signalKeyRepository.confirmDevice(requesterId, deviceId, request.approverDeviceId, request.signature)) {
                SignalKeyRepository.ConfirmDeviceResult.CONFIRMED,
                SignalKeyRepository.ConfirmDeviceResult.ALREADY_CONFIRMED -> call.respondOk()
                SignalKeyRepository.ConfirmDeviceResult.NOT_FOUND ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("设备不存在"))
                SignalKeyRepository.ConfirmDeviceResult.APPROVER_NOT_TRUSTED ->
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("请使用已确认的其他设备批准登录"))
                SignalKeyRepository.ConfirmDeviceResult.INVALID_PROOF ->
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("设备批准证明无效"))
                SignalKeyRepository.ConfirmDeviceResult.INVALID ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("设备 ID 无效"))
            }
        }

        delete("/api/keys/devices/{deviceId}") {
            val requesterId = call.principal<JWTPrincipal>()!!.payload.subject
            val deviceId = call.requireDeviceId() ?: return@delete
            val authSessionId = JwtConfig.authSessionId(call.principal<JWTPrincipal>()!!.payload)
            val currentDeviceId = authSessionId?.let { sessionId ->
                transaction {
                    AuthSessions.selectAll()
                        .where { AuthSessions.id eq sessionId }
                        .firstOrNull()
                        ?.get(AuthSessions.signalDeviceId)
                }
            }
            if (currentDeviceId == deviceId) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("不能移除当前登录设备"))
                return@delete
            }
            val removal = signalKeyRepository.deleteDeviceAndRevokeSessionsGuarded(requesterId, deviceId)
            when (removal.result) {
                SignalKeyRepository.DeleteDeviceResult.NOT_FOUND ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("设备不存在"))
                SignalKeyRepository.DeleteDeviceResult.LAST_CONFIRMED ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("至少保留一个已确认设备"))
                SignalKeyRepository.DeleteDeviceResult.DELETED -> {
                    disconnectUserSessionsByAuthSessionIds(
                        requesterId,
                        removal.revokedSessionIds,
                        "该登录设备已被移除",
                    )
                    call.respondOk()
                }
            }
        }

        get("/api/keys/{userId}/devices/{deviceId}/prekey-bundle") {
            val requesterId = call.principal<JWTPrincipal>()!!.payload.subject
            val targetUserId = call.parameters["userId"].orEmpty()
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
            val requesterId = call.principal<JWTPrincipal>()!!.payload.subject
            val targetUserId = call.parameters["userId"].orEmpty()
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

        get("/api/e2ee/sealed-sender/certificate") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            if (!RuntimeConfigService.isSealedSenderEnabled()) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("sealed sender disabled"))
                return@get
            }
            val deviceId = call.request.queryParameters["deviceId"]?.toIntOrNull() ?: 1
            val issued = SealedSenderCertificateService.issue(userId, deviceId) ?: run {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("failed to issue certificate"))
                return@get
            }
            call.respond(buildJsonObject {
                put("certificate", issued.certificate)
                put("expiresAt", issued.expiresAt)
                put("deviceId", issued.deviceId)
                put("userId", issued.userId)
                put("version", "v1")
            })
        }

        post("/api/e2ee/sealed-sender/verify") {
            val body = call.receiveBoundedTextOrEmpty()
            val certificate = runCatching {
                Json.parseToJsonElement(body).jsonObject["certificate"]?.jsonPrimitive?.content
            }.getOrNull().orEmpty()
            val verified = SealedSenderCertificateService.verify(certificate)
            call.respond(buildJsonObject {
                put("ok", verified != null)
                if (verified != null) {
                    put("userId", verified.userId)
                    put("deviceId", verified.deviceId)
                    put("expiresAt", verified.expiresAt)
                }
            })
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.requireDeviceId(): Int? {
    val deviceId = parameters["deviceId"]?.toIntOrNull()
    if (deviceId == null || deviceId !in 1..255) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("设备 ID 无效"))
        return null
    }
    return deviceId
}

private suspend fun io.ktor.server.application.ApplicationCall.respondOk() {
    respond(buildJsonObject { put("status", "ok") })
}
