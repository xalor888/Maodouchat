package com.maodouchat.server.repository

// 内容管理簇（帖子/评论）：原 AdminManagementRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.common.toPostAdminResponse
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.CommentAdminResponse
import com.maodouchat.server.model.PostAdminResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun AdminManagementRepository.listAdminPosts(
    limit: Int,
    offset: Long,
    authorId: String?,
    status: String?,
    search: String?,
): List<PostAdminResponse> = transaction {
    val query = Posts.selectAll()
    if (!authorId.isNullOrBlank()) query.andWhere { Posts.authorId eq authorId }
    if (!status.isNullOrBlank()) query.andWhere { Posts.status eq status }
    val escapedSearch = search?.let { escapeLikePattern(it) }
    if (escapedSearch != null) query.andWhere { Posts.content like "%$escapedSearch%" }
    val rows = query
        .orderBy(Posts.createdAt to SortOrder.DESC, Posts.id to SortOrder.DESC)
        .limit(limit, offset)
        .toList()
    val authorIds = rows.map { it[Posts.authorId] }.distinct()
    val authorNames = if (authorIds.isEmpty()) {
        emptyMap()
    } else {
        Users.selectAll()
            .where { Users.id inList authorIds }
            .associate { it[Users.id] to it[Users.name] }
    }
    rows.map { it.toPostAdminResponse(authorNames[it[Posts.authorId]].orEmpty()) }
}

fun AdminManagementRepository.listAdminComments(
    limit: Int,
    offset: Long,
    search: String?,
): List<CommentAdminResponse> = transaction {
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
                createdAt = it[PostComments.createdAt],
            )
        }
}
