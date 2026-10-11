package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.PostRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 管理后台内容写操作（动态 / 评论删除）。 */
internal fun Route.configureAdminContentWriteRoutes(
    postRepo: PostRepository,
) {

    delete("/posts/{id}") {
        if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.requireUserId()
        val id = call.requirePathParamOr400("id", "缺少动态 ID") ?: return@delete
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
        val id = call.requirePathParamOr400("id", "缺少评论 ID") ?: return@delete
        val ok = postRepo.deleteCommentForModeration(id)
        if (!ok) return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("评论不存在"))
        recordAdminAudit(actorId, "ADMIN_COMMENT_DELETED", "commentId=$id")
        call.respond(
            buildJsonObject {
                put("status", "deleted")
            }
        )
    }
}
