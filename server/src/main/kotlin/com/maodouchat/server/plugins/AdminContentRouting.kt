package com.maodouchat.server.plugins

import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.CommentAdminResponse
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.PostRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 管理后台「内容管理（动态 / 评论）」子域路由。鉴权 + DTO/查询 + repository 调用 + 错误映射；
 * 删除动态后广播 WS 事件是提交后副作用，不反向回滚已提交的删除。
 */
internal fun Route.configureAdminContentRoutes(postRepo: PostRepository) {
    get("/posts") {
        if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
        val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }
        val search = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
        val posts = transaction {
            val query = Posts.selectAll()
            if (status != null) query.andWhere { Posts.status eq status }
            val escapedSearch = search?.let { escapeLikePattern(it) }
            if (escapedSearch != null) query.andWhere { Posts.content like "%$escapedSearch%" }
            val rows = query
                .orderBy(Posts.createdAt to SortOrder.DESC, Posts.id to SortOrder.DESC)
                .limit(limit, offset)
                .toList()
            val authorIds = rows.map { it[Posts.authorId] }.distinct()
            val authorNames = if (authorIds.isEmpty()) emptyMap() else Users.selectAll()
                .where { Users.id inList authorIds }
                .associate { it[Users.id] to it[Users.name] }
            rows.map { it.toPostAdminResponse(authorNames[it[Posts.authorId]].orEmpty()) }
        }
        call.respond(posts)
    }

    delete("/posts/{id}") {
        if (!call.isAdminUser()) return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
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
        val actorId = call.principal<JWTPrincipal>()!!.payload.subject
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
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
        val search = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
        val comments = transaction {
            val query = (PostComments innerJoin Users).selectAll()
            val escapedSearch = search?.let { escapeLikePattern(it) }
            if (escapedSearch != null) {
                query.andWhere {
                    (PostComments.content like "%$escapedSearch%") or (Users.name like "%$escapedSearch%")
                }
            }
            query.orderBy(PostComments.createdAt to SortOrder.DESC, PostComments.id to SortOrder.DESC)
                .limit(limit, offset)
                .map {
                    CommentAdminResponse(
                        id = it[PostComments.id],
                        postId = it[PostComments.postId],
                        authorId = it[PostComments.authorId],
                        authorName = it[Users.name],
                        content = it[PostComments.content],
                        createdAt = it[PostComments.createdAt]
                    )
                }
        }
        call.respond(comments)
    }
}
