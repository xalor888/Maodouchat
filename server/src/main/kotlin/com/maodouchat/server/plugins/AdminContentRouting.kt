package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.AdminManagementRepository
import com.maodouchat.server.repository.PostRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 管理后台「内容管理（动态 / 评论）」子域路由。鉴权 + DTO/查询 + repository 调用 + 错误映射；
 * 删除动态后广播 WS 事件是提交后副作用，不反向回滚已提交的删除。
 */
internal fun Route.configureAdminContentRoutes(
    postRepo: PostRepository,
    adminManagementRepo: AdminManagementRepository = AdminManagementRepository()
) {
    get("/posts") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseAdminListLimit(call.request.queryParameters)
        val offset = parseAdminListOffset(call.request.queryParameters)
        // status 不用 parseAdminListStatus：此处沿用未 trim 的原语义
        val status = parseNonBlankOrNull(call.request.queryParameters, "status")
        val search = parseAdminListSearch(call.request.queryParameters)
        val posts = adminManagementRepo.listAdminPosts(
            limit = limit,
            offset = offset,
            authorId = null,
            status = status,
            search = search
        )
        call.respond(posts)
    }

    delete("/posts/{id}") {
        if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少动态 ID"))
        val ok = postRepo.deletePostForModeration(id)
        if (!ok) return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
        recordAdminAudit(actorId, "ADMIN_POST_DELETED", "postId=$id")
        broadcastPostDeleted(id)
        call.respond(
            buildJsonObject {
                put("status", "deleted")
            }
        )
    }

    delete("/comments/{id}") {
        if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少评论 ID"))
        val ok = postRepo.deleteCommentForModeration(id)
        if (!ok) return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("评论不存在"))
        recordAdminAudit(actorId, "ADMIN_COMMENT_DELETED", "commentId=$id")
        call.respond(
            buildJsonObject {
                put("status", "deleted")
            }
        )
    }

    get("/comments") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = parseAdminListLimit(call.request.queryParameters)
        val offset = parseAdminListOffset(call.request.queryParameters)
        val search = parseAdminListSearch(call.request.queryParameters)
        val comments = adminManagementRepo.listAdminComments(
            limit = limit,
            offset = offset,
            search = search
        )
        call.respond(comments)
    }
}
