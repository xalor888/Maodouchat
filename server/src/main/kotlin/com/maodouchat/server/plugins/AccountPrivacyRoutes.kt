package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** 隐私与通知：隐私设置、通知偏好。 */
internal fun Route.configureAccountPrivacyRoutes(
    userRepo: UserRepository,
    notificationPreferenceRepo: NotificationPreferenceRepository,
    json: Json,
) {
    authenticate("auth-jwt") {

        get("/api/users/privacy") {
            val userId = call.requireUserId()
            val privacy = userRepo.getPrivacy(userId)
            if (privacy != null) call.respond(privacy)
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
        }

        put("/api/users/privacy") {
            val userId = call.requireUserId()
            // 8.38：封禁用户不得改隐私（与头像/资料/附近位置一致，防关闭 searchable 逃避检索处置）
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            val req = call.receiveJson<UpdatePrivacyRequest>()
            if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@put }
            if (req.defaultPostVisibility != null && !isValidPostVisibility(req.defaultPostVisibility)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("动态可见范围无效"))
                return@put
            }
            val update = userRepo.updatePrivacyWithTransitions(
                userId,
                showOnline = req.showOnline,
                showStatus = req.showStatus,
                searchable = req.searchable,
                defaultPostVisibility = req.defaultPostVisibility,
                onlineVisibility = req.onlineVisibility
            )
            if (update == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                return@put
            }
            if (update.onlineRevoked || update.statusRevoked) {
                broadcastUserVisibilityRevoked(
                    userId = userId,
                    onlineRevoked = update.onlineRevoked,
                    statusRevoked = update.statusRevoked,
                    json = json,
                    userRepo = userRepo
                )
            }
            call.respond(update.privacy)
        }

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
