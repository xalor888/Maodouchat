package com.maodouchat.server.plugins

import com.maodouchat.server.model.AssignUserTagsRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.RiskTagSummary
import com.maodouchat.server.model.RiskTagSummaryResponse
import com.maodouchat.server.model.UserTagAssignmentDto
import com.maodouchat.server.repository.UserTagRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 按用户打标/摘标与风险汇总。 */
fun Application.configureUserTagAssignmentRoutes(userTagRepo: UserTagRepository) {
    routing {
        authenticate("admin-jwt") {
            route("/api/admin") {
                get("/tags/risk-summary") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val tags = userTagRepo.listTags()
                    val riskUsers = tags.filter { it.riskLevel in setOf("HIGH", "CRITICAL") }
                        .map { t ->
                            RiskTagSummary(tagId = t.id, name = t.name, riskLevel = t.riskLevel, userCount = t.userCount)
                        }
                    call.respond(RiskTagSummaryResponse(tags = riskUsers))
                }

                get("/users/{userId}/tags") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val userId = call.requirePathParamOr400("userId", "缺少用户 ID") ?: return@get
                    val assignments = userTagRepo.userAssignments(userId)
                    call.respond(assignments.map { it.toDto() })
                }

                post("/users/{userId}/tags") {
                    if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.requireUserId()
                    val userId = call.requirePathParamOr400("userId", "缺少用户 ID") ?: return@post
                    val req = call.receiveAdminJson<AssignUserTagsRequest>()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                    if (req.tagIds.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("标签列表不能为空"))
                    if (req.tagIds.size > MAX_TAGS_PER_ASSIGNMENT) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("单次打标数量超限"))
                    }
                    val tags = userTagRepo.listTags()
                    // 9.140：目标用户不存在时 404（此前 assignTags 撞悬空 FK 抛约束异常 → 500）
                    val assigned = userTagRepo.assignTags(userId, req.tagIds, "MANUAL", actorId)
                        ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
                    val risky = tags.filter { it.id in req.tagIds && it.riskLevel in setOf("HIGH", "CRITICAL") }
                    recordAdminAudit(
                        actorId, "USER_TAGS_ASSIGNED",
                        "userId=$userId;tags=${req.tagIds.joinToString(",")};risk=${risky.map { it.id }}"
                    )
                    call.respond(assigned.map { it.toDto() })
                }

                delete("/users/{userId}/tags/{tagId}") {
                    if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.requireUserId()
                    val userId = call.requirePathParamOr400("userId", "缺少用户 ID") ?: return@delete
                    val tagId = call.requirePathParamOr400("tagId", "缺少标签 ID") ?: return@delete
                    if (!userTagRepo.detachTag(userId, tagId)) {
                        return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("该用户未打此标签"))
                    }
                    recordAdminAudit(actorId, "USER_TAG_DETACHED", "userId=$userId;tagId=$tagId")
                    call.respond(buildJsonObject { put("ok", true) })
                }
            }
        }
    }
}

private fun UserTagRepository.AssignmentRow.toDto(): UserTagAssignmentDto = UserTagAssignmentDto(
private const val MAX_TAGS_PER_ASSIGNMENT = 20
