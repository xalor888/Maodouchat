package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.repository.pushTokens
import com.maodouchat.server.repository.signalDevices
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.*

/** 管理后台：单用户会话/设备/推送查询（从 AdminUserSessionRoutes 按域拆出）。 */
internal fun Route.configureAdminUserSessionQueryRoutes(
    authTokenRepo: AuthTokenRepository,
    adminManagementRepo: com.maodouchat.server.repository.AdminManagementRepository,
) {

get("/users/{id}/sessions") {
    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
    val id = call.requirePathParamOr400("id", "missing user id") ?: return@get
    val includeRevoked = parseQueryFlagOne(call.request.queryParameters, "includeRevoked")
    val sessions = authTokenRepo.listActiveRefreshSessions(id, includeRevoked = includeRevoked).map { s ->
        buildJsonObject {
            put("tokenHashPrefix", s.tokenHashPrefix)
            put("createdAt", s.createdAt)
            put("expiresAt", s.expiresAt)
            s.revokedAt?.let { put("revokedAt", it) }
            put("active", s.revokedAt == null && s.expiresAt > System.currentTimeMillis())
        }
    }
    val devices = adminManagementRepo.signalDevices(id).map { row ->
        buildJsonObject {
            put("deviceId", row.deviceId)
            put("deviceName", row.deviceName)
            put("status", row.status)
            put("lastSeenAt", row.lastSeenAt)
            put("createdAt", row.createdAt)
        }
    }
    val push = adminManagementRepo.pushTokens(id).map { row ->
        buildJsonObject {
            put("deviceId", row.deviceId)
            put("platform", row.platform)
            put("updatedAt", row.updatedAt)
        }
    }
    call.respond(
    buildJsonObject {
put("userId", id)
put("refreshSessions", JsonArray(sessions))
put("activeRefreshCount", authTokenRepo.countActiveRefreshSessions(id))
put("signalDevices", JsonArray(devices))
put("pushTokens", JsonArray(push))
    }
)
}
}
