package com.maodouchat.server.plugins

import com.maodouchat.server.model.CreateModerationRuleRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateModerationRuleRequest
import com.maodouchat.server.repository.ModerationRuleRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun Route.configureAdminModerationRuleRoutes(
    moderationRuleRepo: ModerationRuleRepository,
) {
// 风控规则 CRUD
    get("/moderation-rules") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val rules = moderationRuleRepo.getRules()
        call.respond(rules)
    }

    post("/moderation-rules") {
        if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val req = call.receiveAdminJson<CreateModerationRuleRequest>()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
        val id = runCatching { moderationRuleRepo.createRule(req) }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("规则参数无效"))
        recordAdminAudit(call.requireUserId(), "ADMIN_RULE_CREATED", "ruleId=$id")
        call.respond(
            buildJsonObject {
                put("id", id)
                put("status", "created")
            }
        )
    }

    put("/moderation-rules/{id}") {
        if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val id = call.requirePathParamOr400("id", "缺少规则 ID") ?: return@put
        val req = call.receiveAdminJson<UpdateModerationRuleRequest>()
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
        if (!moderationRuleRepo.ruleExists(id)) {
            return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("规则不存在"))
        }
        val updated = moderationRuleRepo.updateRule(id, req)
            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("规则参数无效"))
        recordAdminAudit(call.requireUserId(), "ADMIN_RULE_UPDATED", "ruleId=$id")
        call.respond(updated)
    }

    delete("/moderation-rules/{id}") {
        if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val id = call.requirePathParamOr400("id", "缺少规则 ID") ?: return@delete
        if (!moderationRuleRepo.deleteRule(id)) {
            return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("规则不存在"))
        }
        recordAdminAudit(call.requireUserId(), "ADMIN_RULE_DELETED", "ruleId=$id")
        call.respond(
            buildJsonObject {
                put("status", "deleted")
            }
        )
    }
}
