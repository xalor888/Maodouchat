package com.maodouchat.server.plugins

import com.maodouchat.server.model.CreateUserTagRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateUserTagRequest
import com.maodouchat.server.model.UserTagDto
import com.maodouchat.server.repository.UserTagRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/** 用户标签 CRUD 与标签下用户查询。 */
fun Application.configureUserTagCrudRoutes(userTagRepo: UserTagRepository) {
    routing {
        authenticate("admin-jwt") {
            route("/api/admin") {
                get("/user-tags") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val q = parseAdminListSearch(call.request.queryParameters)
                    val tags = userTagRepo.listTags(q)
                    call.respond(tags.map { it.toDto() })
                }

                post("/user-tags") {
                    if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.requireUserId()
                    val req = call.receiveAdminJson<CreateUserTagRequest>()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
                    val name = call.requireNonBlankValueOr400(req.name, "标签名称不能为空") ?: return@post
                    val riskLevel = req.riskLevel.uppercase().take(20)
                    if (riskLevel !in setOf("NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL")) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("风控级别非法"))
                    }
                    val duplicate = userTagRepo.listTags().any { it.name.equals(name.trim(), ignoreCase = true) }
                    if (duplicate) return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("同名标签已存在"))
                    val tag = userTagRepo.createTag(
                        name = name, color = req.color, description = req.description,
                        riskLevel = riskLevel, isSystem = false, createdBy = actorId
                    )
                    recordAdminAudit(actorId, "USER_TAG_CREATED", "tagId=${tag.id};name=${tag.name};risk=$riskLevel")
                    call.respond(HttpStatusCode.Created, tag.toDto())
                }

                put("/user-tags/{id}") {
                    if (!call.isAdminUser()) return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.requireUserId()
                    val id = call.requirePathParamOr400("id", "缺少标签 ID") ?: return@put
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
                    val id = call.requirePathParamOr400("id", "缺少标签 ID") ?: return@delete
                    if (!userTagRepo.deleteTag(id)) {
                        return@delete call.respond(HttpStatusCode.Conflict, ErrorResponse("系统内置标签不可删除或标签不存在"))
                    }
                    recordAdminAudit(actorId, "USER_TAG_DELETED", "tagId=$id")
                    call.respond(buildJsonObject { put("ok", true) })
                }

                get("/user-tags/{id}/users") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val id = call.requirePathParamOr400("id", "缺少标签 ID") ?: return@get
                    val limit = parseAdminListLimit(call.request.queryParameters)
                    val offset = parseAdminListOffset(call.request.queryParameters)
                    val q = parseAdminListSearch(call.request.queryParameters)
                    val users = userTagRepo.listUsersByTag(id, q, limit, offset)
                    call.respond(users.map { it.toDto() })
                }
            }
        }
    }
}

private fun UserTagRepository.TagRow.toDto(): UserTagDto = UserTagDto(
