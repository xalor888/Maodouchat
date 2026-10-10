package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.SealedSenderCertificateService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Sealed-sender 证书签发与校验（不碰设备密钥表）。 */
internal fun Route.configureSealedSenderRoutes() {
    authenticate("auth-jwt") {
        get("/api/e2ee/sealed-sender/certificate") {
            val userId = call.requireUserId()
            if (!RuntimeConfigService.isSealedSenderEnabled()) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("sealed sender disabled"))
                return@get
            }
            val deviceId = parseIntOrDefault(call.request.queryParameters, "deviceId", 1)
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
            val certificate = parseSealedSenderCertificate(body)
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
