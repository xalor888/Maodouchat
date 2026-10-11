package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** 动态读取：列表/详情。 */
internal fun Route.configureSocialPostReadRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
) {
    authenticate("auth-jwt") {
            // 发现页 / 动态 API

            get("/api/posts") {

                if (!RuntimeConfigService.isPostsEnabled()) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("posts_disabled"))
                    return@get
                }
                val userId = call.requireUserId()
                val limit = parseAdminListLimit(call.request.queryParameters, defaultLimit = 30, maxLimit = 50)
                val before = parseSocialFeedBefore(call.request.queryParameters)
                val beforeId = parseSocialFeedBeforeId(call.request.queryParameters, before)
                val authorId = parseRawOrNull(call.request.queryParameters, "authorId")
                if (authorId != null) {
                    call.respond(postRepo.getPostsByAuthor(userId, authorId, limit, before, beforeId))
                } else {
                    call.respond(postRepo.getFeed(userId, limit, before, beforeId))
                }
            }

            get("/api/posts/{id}") {
                val userId = call.requireUserId()
                val postId = call.requirePathParamOr400("id", "缺少动态 ID") ?: return@get
                val post = postRepo.getPostById(postId, userId)
                if (post == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("动态不存在"))
                else call.respond(post)
            }
    }
}
