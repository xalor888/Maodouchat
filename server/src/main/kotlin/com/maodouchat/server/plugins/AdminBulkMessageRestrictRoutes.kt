package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.service.DispositionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 批量禁发消息（按天/按截止时间/清除）。 */
internal fun Route.configureAdminBulkMessageRestrictRoutes(
    userDispositionService: com.maodouchat.server.service.UserDispositionService,
) {
    post("/users/bulk-message-restrict") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val ids = parseAdminIds(obj)
            val days = parseAdminBulkDays(obj, 0, DispositionService.MAX_MESSAGE_RESTRICT_DAYS)
            if (ids.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            }
            val until = if (days <= 0) 0L else System.currentTimeMillis() + days * 86_400_000L
            val result = userDispositionService.bulkExtend(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.MESSAGE, until, "ADMIN_BULK_MESSAGE_RESTRICT", { e -> "days=$days;until=$e" })
            val updated = result.updated
            val skipped = result.skipped
            call.respond(
            buildJsonObject {
    put("ok", true)
    putJsonElement("updated", updated)
    putJsonElement("skipped", skipped)
    put("until", until)
    put("count", updated.size)
            }
        )
        }

    post("/users/bulk-message-unrestrict") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        val actorId = call.requireUserId()
        val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
        val obj = call.requireJsonObjectOr400(body) ?: return@post
        val ids = parseAdminIds(obj)
        if (ids.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
        }
        val result = userDispositionService.bulkClear(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.MESSAGE, "ADMIN_BULK_MESSAGE_UNRESTRICT", "cleared")
        val updated = result.updated
        val skipped = result.skipped
        call.respond(
        buildJsonObject {
put("ok", true)
putJsonElement("updated", updated)
putJsonElement("skipped", skipped)
put("count", updated.size)
        }
    )
    }

    post("/users/bulk-message-restrict-days") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            // 9.131：上限改用策略常量——此前硬编码 3650 天（10 年）绕过
            // DispositionService.MAX_MESSAGE_RESTRICT_DAYS(90) 的处置上限
            val days = parseAdminBulkDays(obj, 1, DispositionService.MAX_MESSAGE_RESTRICT_DAYS)
            val ids = parseAdminIds(obj)
            if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            val until = System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L
            val result = userDispositionService.bulkExtend(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.MESSAGE, until, "ADMIN_BULK_MSG_RESTRICT_DAYS", { e -> "days=$days until=$e" })
            val updated = result.updated
            val skipped = result.skipped
            call.respond(
            buildJsonObject {
    put("ok", true)
    putJsonElement("updated", updated)
    putJsonElement("skipped", skipped)
    put("until", until)
    put("days", days)
    put("count", updated.size)
            }
        )
        }

    post("/users/bulk-clear-message-restrict") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val ids = parseAdminIds(obj)
            if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            val result = userDispositionService.bulkClear(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.MESSAGE, "ADMIN_BULK_CLEAR_MSG_RESTRICT", "cleared")
            val updated = result.updated
            val skipped = result.skipped
            call.respond(
            buildJsonObject {
    put("ok", true)
    putJsonElement("updated", updated)
    putJsonElement("skipped", skipped)
    put("count", updated.size)
            }
        )
        }

    post("/users/bulk-set-message-restrict-until") {
            if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            val actorId = call.requireUserId()
            val body = runCatching { call.receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) }.getOrNull().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val ids = parseAdminIds(obj)
            val until = parseAdminBulkTimestampMs(obj, "until", "untilMs").coerceAtLeast(0L)
            // 8.37：与单用户端点一致的时间合法性校验（此前只 coerceAtLeast(0)，
            // 过去时间戳被静默写成已过期限制，Long.MAX_VALUE 绕过 10 年上限）
            val now = System.currentTimeMillis()
            if (until > now + com.maodouchat.server.service.DispositionService.MAX_MESSAGE_RESTRICT_DAYS * 86_400_000L ||
                (until in 1..now)
            ) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("禁发消息截止时间无效"))
            }
            if (ids.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("userIds required"))
            val result = userDispositionService.bulkSet(actorId, ids, com.maodouchat.server.repository.UserRepository.UserDispositionField.MESSAGE, until, "ADMIN_BULK_SET_MSG_RESTRICT_UNTIL", { _ -> "until=$until" })
            val updated = result.updated
            val skipped = result.skipped
            call.respond(
            buildJsonObject {
    put("ok", true)
    putJsonElement("updated", updated)
    putJsonElement("skipped", skipped)
    put("until", until)
    put("count", updated.size)
            }
        )
        }
}
