package com.maodouchat.server.service

// 动态评论单查簇：按 id 查询与评论 DTO 组装（原 FeedCommentQueries 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.CommentLikes
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.PostCommentResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun FeedQueryService.getCommentAuthorId(commentId: String): String? {
    return transaction {
        PostComments.selectAll().where { PostComments.id eq commentId }.firstOrNull()?.get(PostComments.authorId)
    }
}


internal fun FeedQueryService.getCommentById(
    commentId: String,
    currentUserId: String,
    blockedUserIds: Set<String> = emptySet()
): PostCommentResponse? {
    return (PostComments innerJoin Users)
        .selectAll()
        .where { PostComments.id eq commentId }
        .firstOrNull()
        ?.let {
            enrichCommentLikes(
                listOf(it.toCommentResponse(currentUserId)),
                currentUserId,
                blockedUserIds
            ).firstOrNull()
        }
}


fun FeedQueryService.getComment(commentId: String, currentUserId: String): PostCommentResponse? {
    return transaction {
        val blockedUserIds = SocialGraphService.blockedEitherWayUserIds(currentUserId)
        getCommentById(commentId, currentUserId, blockedUserIds)
    }
}


internal fun ResultRow.toCommentResponse(currentUserId: String): PostCommentResponse {
    val commentId = this[PostComments.id]
    return PostCommentResponse(
        id = commentId,
        postId = this[PostComments.postId],
        author = toPublicUser(),
        content = this[PostComments.content],
        createdAt = this[PostComments.createdAt],
        isMine = this[PostComments.authorId] == currentUserId,
        parentId = this[PostComments.parentId],
        // 点赞数/已赞在 enrichCommentLikes 批量填充（避免 N+1）
        likeCount = 0,
        likedByMe = false
    )
}


internal fun FeedQueryService.enrichCommentLikes(
    comments: List<PostCommentResponse>,
    userId: String,
    blockedUserIds: Set<String> = emptySet()
): List<PostCommentResponse> {
    if (comments.isEmpty()) return comments
    val ids = comments.map { it.id }
    val countExpr = CommentLikes.commentId.count()
    val base = CommentLikes.select(CommentLikes.commentId, countExpr)
        .where { CommentLikes.commentId inList ids }
    val query = if (blockedUserIds.isEmpty()) {
        base
    } else {
        base.andWhere { CommentLikes.userId notInList blockedUserIds.toList() }
    }
    val likeCounts = query
        .groupBy(CommentLikes.commentId)
        .associate { it[CommentLikes.commentId] to it[countExpr].toInt() }
    val likedByMe = CommentLikes
        .select(CommentLikes.commentId)
        .where { (CommentLikes.commentId inList ids) and (CommentLikes.userId eq userId) }
        .map { it[CommentLikes.commentId] }
        .toSet()
    return comments.map {
        it.copy(likeCount = likeCounts[it.id] ?: 0, likedByMe = it.id in likedByMe)
    }
}
