package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.NotificationPreferenceRepository
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/** 通知偏好：读写 /api/users/notification-settings。 */
internal fun Route.configureAccountNotificationSettingsRoutes(
    notificationPreferenceRepo: NotificationPreferenceRepository,
) {
    authenticate("auth-jwt") {

        get("/api/users/notification-settings") {
            val userId = call.requireUserId()
            call.respond(notificationPreferenceRepo.getSettings(userId))
        }

        put("/api/users/notification-settings") {
            val userId = call.requireUserId()
            val req = call.receiveJson<NotificationSettingsRequest>()
            if (req == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@put
            }
            call.respond(notificationPreferenceRepo.updateSettings(userId, req))
        }
    }
}
