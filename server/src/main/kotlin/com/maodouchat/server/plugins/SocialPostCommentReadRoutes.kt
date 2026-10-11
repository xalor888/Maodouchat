package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.PostRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// 评论读：列表查询（从 SocialPostCommentRoutes 按域拆出）。
internal fun Route.configureSocialPostCommentReadRoutes(
    postRepo: PostRepository,
) {
    authenticate("auth-jwt") {
            get("/api/posts/{id}/comments") {
                val userId = call.requireUserId()
                val postId = call.requirePathParamOr400("id", "缺少动态 ID") ?: return@get
                val limit = parseAdminListLimit(call.request.queryParameters, maxLimit = 100)
                val before = parseSocialFeedBefore(call.request.queryParameters)
                val beforeId = parseSocialFeedBeforeId(call.request.queryParameters, before)
                val comments = postRepo.getComments(postId, userId, limit, before, beforeId)
                if (comments == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                else call.respond(comments)
            }
    }
}
