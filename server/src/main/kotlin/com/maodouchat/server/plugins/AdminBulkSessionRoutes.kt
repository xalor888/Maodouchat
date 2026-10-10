package com.maodouchat.server.plugins

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 批量会话处置：强制登出 / token 版本推进 / 关闭 TOTP。 */
internal fun Route.configureAdminBulkSessionRoutes(
    authTokenRepo: AuthTokenRepository,
    adminManagementRepo: com.maodouchat.server.repository.AdminManagementRepository,
    userDispositionService: com.maodouchat.server.service.UserDispositionService,
) {
    post("/users/bulk-force-logout") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.requireUserId()
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val ids = parseAdminBulkIdList(obj, "userIds", 200)
        if (ids.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        }
        val skippedAdmins = mutableListOf<String>()
        val skippedMissing = mutableListOf<String>()
        val okIds = mutableListOf<String>()
        ids.forEach { id ->
            if (AdminAccess.isAdmin(id) && id != actorId) {
                skippedAdmins += id
                return@forEach
            }
            if (authTokenRepo.rotateAccessTokenVersion(id) == 0L) {
                skippedMissing += id
                return@forEach
            }
            bestEffortAdminDisconnect { disconnectUserSessions(id, "admin bulk force logout") }
            okIds += id
        }
        adminManagementRepo.recordAudit(
            actorId = actorId,
            userId = actorId,
            action = "ADMIN_BULK_FORCE_LOGOUT",
            detail = "ok=${okIds.size};skippedAdmins=${skippedAdmins.size}",
        )
        call.respond(
        buildJsonObject {
put("ok", true)
putJsonElement("loggedOut", okIds)
putJsonElement("skippedAdmins", skippedAdmins)
putJsonElement("skippedMissing", skippedMissing)
put("count", okIds.size)
        }
    )
    }

    post("/users/bulk-force-token-bump") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val ids = parseAdminIds(obj)
            if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            val updated = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            val bumped = mutableListOf<Triple<String, Long, Long>>()
            ids.forEach { id ->
                if (id == actorId || AdminAccess.isAdmin(id)) {
                    skipped += id
                    return@forEach
                }
                val next = authTokenRepo.rotateAccessTokenVersion(id)
                val ok = next > 0L
                if (ok) {
                    bumped += Triple(id, next, System.currentTimeMillis())
                }
                if (ok) updated += id else skipped += id
            }
            if (bumped.isNotEmpty()) {
                adminManagementRepo.recordAuditBatch(
                    actorId = actorId,
                    action = "ADMIN_BULK_TOKEN_BUMP",
                    entries = bumped.map { Triple(it.first, "version=${it.second}", it.third) },
                )
                bumped.forEach { (id, _, _) ->
                    try {
                        disconnectUserSessions(id, "admin bulk token bump")
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                    }
                }
            }
            call.respond(
            buildJsonObject {
    put("ok", true)
    putJsonElement("updated", updated)
    putJsonElement("skipped", skipped)
    put("count", updated.size)
            }
        )
        }

    post("/users/bulk-disable-totp") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.requireUserId()
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val ids = parseAdminIds(obj)
        if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        val result = userDispositionService.bulkDisableTotp(actorId, ids)
        val updated = result.updated
        val skipped = result.skipped
        recordAdminAudit(actorId = actorId, action = "bulk_disable_totp", detail = "count=${updated.size}")
        call.respond(
        buildJsonObject {
put("ok", true)
putJsonElement("updated", updated)
putJsonElement("skipped", skipped)
put("count", updated.size)
        }
    )
    }
}
