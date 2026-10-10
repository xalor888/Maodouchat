package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

// 推送设备 ID 校验：两处 handler 都在请求里内联编译同一正则，提到文件级复用。
private val pushDeviceIdRegex = Regex("^[A-Za-z0-9._:-]{1,100}$")

/** 推送：verify-key 派生密钥查询、push token 注册/注销。 */
internal fun Route.configureAccountPushRoutes(
    pushTokenRepo: PushTokenRepository,
) {
    authenticate("auth-jwt") {

        get("/api/push/verify-key") {
            val userId = call.requireUserId()
            val secret = com.maodouchat.server.config.ServerConfig.pushHmacSecret
            if (secret.isBlank() || secret.startsWith("dev-only-")) {
                call.respond(buildJsonObject { put("key", JsonNull) })
            } else {
                call.respond(buildJsonObject { put("key", com.maodouchat.server.service.FcmPushService.pushKeyForUser(userId)) })
            }
        }

        post("/api/users/push-tokens") {
            val userId = call.requireUserId()
            val authSessionId = call.requireAuthSessionId()
            val req = call.receiveJson<RegisterPushTokenRequest>()
            val deviceId = req?.deviceId?.trim().orEmpty()
            val token = req?.token?.trim().orEmpty()
            val platform = req?.platform?.trim()?.uppercase().orEmpty()
            if (req == null || !deviceId.matches(pushDeviceIdRegex) ||
                token.length !in 32..512 || token.any(Char::isWhitespace) ||
                platform != "ANDROID" || req.timezoneOffsetMinutes !in -1080..1080
            ) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("推送令牌无效"))
                return@post
            }
            if (!pushTokenRepo.register(
                    userId,
                    deviceId,
                    token,
                    platform,
                    req.timezoneOffsetMinutes,
                    authSessionId
                )
            ) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("登录会话已被撤销"))
                return@post
            }
            call.respondOk()
        }

        delete("/api/users/push-tokens") {
            val userId = call.requireUserId()
            val req = call.receiveJson<RemovePushTokenRequest>()
            val deviceId = req?.deviceId?.trim().orEmpty()
            if (!deviceId.matches(pushDeviceIdRegex)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("设备标识无效"))
                return@delete
            }
            pushTokenRepo.remove(userId, deviceId)
            call.respondOk()
        }
    }
}
