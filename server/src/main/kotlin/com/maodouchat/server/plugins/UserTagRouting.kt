package com.maodouchat.server.plugins

import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.model.AssignUserTagsRequest
import com.maodouchat.server.model.CreateUserTagRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.RiskTagSummary
import com.maodouchat.server.model.RiskTagSummaryResponse
import com.maodouchat.server.model.UpdateUserTagRequest
import com.maodouchat.server.model.UserTagAssignmentDto
import com.maodouchat.server.model.UserTagDto
import com.maodouchat.server.repository.UserTagRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * 用户标签子域路由：标签 CRUD、风险联动、按用户打标/摘标、风险汇总。
 * 从 AdminEnhanceRouting 拆出（B13 子项 3：管理 route 按内容审核拆分）。
 */
fun Application.configureUserTagRoutes(userTagRepo: UserTagRepository) {
    routing {
        authenticate("admin-jwt") {
            route("/api/admin") {
                // ═══ 用户标签 + 风控联动 ═══
                get("/user-tags") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
                    val tags = userTagRepo.listTags(q)
                    call.respond(tags.map { it.toDto() })
                }

                post("/user-tags") {
                    if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.requireUserId()
                    val req = call.receiveAdminJson<CreateUserTagRequest>()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                    if (req.name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("标签名称不能为空"))
                    val riskLevel = req.riskLevel.uppercase().take(20)
                    if (riskLevel !in setOf("NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL")) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("风控级别非法"))
                    }
                    val duplicate = userTagRepo.listTags().any { it.name.equals(req.name.trim(), ignoreCase = true) }
                    if (duplicate) return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("同名标签已存在"))
                    val tag = userTagRepo.createTag(
                        name = req.name, color = req.color, description = req.description,
                        riskLevel = riskLevel, isSystem = false, createdBy = actorId
                    )
                    recordAdminAudit(actorId, "USER_TAG_CREATED", "tagId=${tag.id};name=${tag.name};risk=$riskLevel")
                    call.respond(HttpStatusCode.Created, tag.toDto())
                }

                put("/user-tags/{id}") {
                    if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.requireUserId()
                    val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少标签 ID"))
                    val req = call.receiveAdminJson<UpdateUserTagRequest>()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                    val riskLevel = req.riskLevel?.uppercase()?.take(20)
                    if (riskLevel != null && riskLevel !in setOf("NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL")) {
                        return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("风控级别非法"))
                    }
                    val updated = userTagRepo.updateTag(id, req.name, req.color, req.description, riskLevel)
                        ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("标签不存在"))
                    recordAdminAudit(actorId, "USER_TAG_UPDATED", "tagId=$id;name=${updated.name};risk=${updated.riskLevel}")
                    call.respond(updated.toDto())
                }

                delete("/user-tags/{id}") {
                    if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.requireUserId()
                    val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少标签 ID"))
                    if (!userTagRepo.deleteTag(id)) {
                        return@delete call.respond(HttpStatusCode.Conflict, ErrorResponse("系统内置标签不可删除或标签不存在"))
                    }
                    recordAdminAudit(actorId, "USER_TAG_DELETED", "tagId=$id")
                    call.respond(buildJsonObject { put("ok", true) })
                }

                get("/user-tags/{id}/users") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少标签 ID"))
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                    val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                    val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
                    val users = userTagRepo.listUsersByTag(id, q, limit, offset)
                    call.respond(users.map { it.toDto() })
                }

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
                    val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
                    val assignments = userTagRepo.userAssignments(userId)
                    call.respond(assignments.map { it.toDto() })
                }

                post("/users/{userId}/tags") {
                    if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.requireUserId()
                    val userId = call.parameters["userId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
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
                    // 风控联动：打上 HIGH/CRITICAL 风险标签时写入风险事件队列，进入人工复核
                    val risky = tags.filter { it.id in req.tagIds && it.riskLevel in setOf("HIGH", "CRITICAL") }
                    if (risky.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        transaction {
                            risky.forEach { tag ->
                                RiskEvents.insert {
                                    it[RiskEvents.id] = UUID.randomUUID().toString()
                                    it[RiskEvents.userId] = userId
                                    it[RiskEvents.sourceValue] = "USER_TAG"
                                    it[RiskEvents.ruleId] = tag.id
                                    it[RiskEvents.action] = "TAG_RISK"
                                    it[RiskEvents.matched] = "tag=${tag.name};risk=${tag.riskLevel}"
                                    it[RiskEvents.referenceId] = tag.id
                                    it[RiskEvents.needsReview] = true
                                    it[RiskEvents.createdAt] = now
                                }
                            }
                        }
                    }
                    recordAdminAudit(
                        actorId, "USER_TAGS_ASSIGNED",
                        "userId=$userId;tags=${req.tagIds.joinToString(",")};risk=${risky.map { it.id }}"
                    )
                    call.respond(assigned.map { it.toDto() })
                }

                delete("/users/{userId}/tags/{tagId}") {
                    if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.requireUserId()
                    val userId = call.parameters["userId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少用户 ID"))
                    val tagId = call.parameters["tagId"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少标签 ID"))
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

private fun UserTagRepository.TagRow.toDto(): UserTagDto = UserTagDto(
    id = id, name = name, color = color, description = description,
    isSystem = isSystem, riskLevel = riskLevel, createdAt = createdAt,
    updatedAt = updatedAt, userCount = userCount
)

private fun UserTagRepository.AssignmentRow.toDto(): UserTagAssignmentDto = UserTagAssignmentDto(
    userId = userId, tagId = tagId, source = source, assignedBy = assignedBy, createdAt = createdAt
)
private const val MAX_TAGS_PER_ASSIGNMENT = 20
