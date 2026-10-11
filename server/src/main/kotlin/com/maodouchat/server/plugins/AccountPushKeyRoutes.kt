package com.maodouchat.server.plugins

import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** 推送 verify-key：派生密钥查询。 */
internal fun Route.configureAccountPushKeyRoutes() {
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
    }
}
