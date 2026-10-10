package com.maodouchat.server.plugins

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 会话 token 前缀校验：撤销入口每次请求都在重新编译，提到文件级复用。
private val sessionTokenPrefixRegex = Regex("^[0-9a-fA-F]{12,64}$")

/** 单用户会话治理：会话/设备/推送查询、撤销、强制登出、关闭 TOTP。 */
internal fun Route.configureAdminUserSessionRoutes(
    userRepo: UserRepository,
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

    post("/users/{id}/sessions/revoke") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.requireUserId()
        val id = call.requirePathParamOr400("id", "missing user id") ?: return@post
        if (AdminAccess.isAdmin(id) && id != actorId) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("cannot revoke master admin sessions"))
        }
        if (userRepo.getById(id) == null) {
            return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("user not found"))
        }
        val body = call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("request body is too large or unreadable"))
        val obj = if (body.isBlank()) null else {
            call.requireJsonObjectOr400(body) ?: return@post
        }
        val prefix = when (val parsed = parseAdminRevokePrefix(obj?.get("tokenHashPrefix"))) {
            is AdminRevokePrefixResult.Ok -> parsed.prefix
            is AdminRevokePrefixResult.Invalid -> return@post call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(parsed.message),
            )
        }
        val revokeAll = when (val parsed = parseAdminRevokeAll(obj?.get("all"))) {
            is AdminRevokeAllResult.Ok -> parsed.revokeAll
            is AdminRevokeAllResult.Invalid -> return@post call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(parsed.message),
            )
        }
        if (!revokeAll && prefix.isBlank()) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("tokenHashPrefix or all=true is required")
            )
        }
        if (revokeAll && prefix.isNotBlank()) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("tokenHashPrefix and all=true are mutually exclusive")
            )
        }
        if (!revokeAll && !prefix.matches(sessionTokenPrefixRegex)) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("tokenHashPrefix must be 12-64 hexadecimal characters")
            )
        }
        val (revoked, revokedSessionIds) = if (revokeAll) {
            val activeCount = authTokenRepo.countActiveRefreshSessions(id)
            authTokenRepo.rotateAccessTokenVersion(id)
            activeCount to emptySet<String>()
        } else {
            val result = authTokenRepo.revokeByHashPrefixWithSessions(id, prefix)
            result.count to result.sessionIds
        }
        if (revokeAll) {
            bestEffortAdminDisconnect { disconnectUserSessions(id, "admin session revoke") }
        } else if (revokedSessionIds.isNotEmpty()) {
            bestEffortAdminDisconnect {
                disconnectUserSessionsByAuthSessionIds(id, revokedSessionIds, "admin session revoke")
            }
        }
        adminManagementRepo.recordAudit(
            actorId = actorId,
            userId = id,
            action = "ADMIN_SESSION_REVOKE",
            detail = if (revokeAll) "all=$revoked" else "prefix=$prefix count=$revoked",
        )
        call.respond(
        buildJsonObject {
put("status", "ok")
put("revoked", revoked)
put("userId", id)
        }
    )
    }

    post("/users/{id}/force-logout") {
                    if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                    val actorId = call.requireUserId()
                    val id = call.requirePathParamOr400("id", "missing user id") ?: return@post
                    if (AdminAccess.isAdmin(id) && id != actorId) {
                        return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("cannot force-logout master admin"))
                    }
                    if (authTokenRepo.rotateAccessTokenVersion(id) == 0L) {
                        return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("user not found"))
                    }
                    bestEffortAdminDisconnect { disconnectUserSessions(id, "admin force logout") }
                    adminManagementRepo.recordAudit(actorId, id, "ADMIN_FORCE_LOGOUT", "force logout")
                    call.respond(
                    buildJsonObject {
    put("status", "ok")
    put("userId", id)
                    }
                )
                }

    post("/users/{id}/disable-totp") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.requireUserId()
        val id = call.requirePathParamOr400("id", "missing user id") ?: return@post
        if (AdminAccess.isAdmin(id) && id != actorId) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("cannot disable TOTP for another master admin"))
        }
        if (!userRepo.disableTotp(actorId, id)) return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("user not found"))
        call.respond(
        buildJsonObject {
put("status", "ok")
put("userId", id)
put("totpEnabled", false)
        }
    )
    }
}
