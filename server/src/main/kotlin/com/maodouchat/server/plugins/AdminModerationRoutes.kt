package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/** 管理审核：规则读写、审核事件/确认。 */
internal fun Route.configureAdminReportModerationRoutes(
    userRepo: UserRepository,
    moderationRuleRepo: ModerationRuleRepository,
) {
    authenticate("auth-jwt") {

        get("/api/admin/moderation/rules") {
            val uid = call.requireUserId()
            if (!hasContentModerationAccess(userRepo, uid)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                return@get
            }
            call.respond(moderationRuleRepo.getRules())
        }

        put("/api/admin/moderation/rules/{ruleId}") {
            val uid = call.requireUserId()
            if (!hasContentModerationAccess(userRepo, uid)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                return@put
            }
            val ruleId = parseRawOrEmpty(call.parameters, "ruleId")
            val req = call.receiveJsonOr400<UpdateModerationRuleRequest>() ?: return@put
            // 8.32 一致性：资源不存在 404、参数问题 400（此前合并为一个 400）
            if (!moderationRuleRepo.ruleExists(ruleId)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("规则不存在"))
                return@put
            }
            val updated = moderationRuleRepo.updateRule(ruleId, req)
            if (updated == null) call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
            else call.respond(updated)
        }

        get("/api/admin/moderation/events") {
            val uid = call.requireUserId()
            if (!hasContentModerationAccess(userRepo, uid)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                return@get
            }
            val limit = parseAdminListLimit(call.request.queryParameters, defaultLimit = 100)
            val needsReview = parseNeedsReview(call.request.queryParameters)
            call.respond(moderationRuleRepo.getRiskEvents(limit, needsReview))
        }

        post("/api/admin/moderation/events/{eventId}/ack") {
            val uid = call.requireUserId()
            if (!hasContentModerationAccess(userRepo, uid)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要审核员权限"))
                return@post
            }
            val eventId = parseRawOrEmpty(call.parameters, "eventId")
            if (!moderationRuleRepo.acknowledgeRiskEvent(eventId)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("风险事件不存在"))
                return@post
            }
            call.respond(
            buildJsonObject {
put("status", "ok")
            }
        )
        }
    }
}
