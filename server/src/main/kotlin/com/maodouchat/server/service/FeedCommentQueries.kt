package com.maodouchat.server.service

import com.maodouchat.server.db.CommentLikes
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.PostLikes
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.PostCommentResponse
import com.maodouchat.server.model.UserResponse
import com.maodouchat.server.repository.PostVisibility
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 动态评论/点赞查询簇：评论分页、点赞者列表、评论点赞批量填充（自 FeedQueryService 迁出，零行为改动）。 */
/** 评论分页迭代上限：游标按可见评论推进后，前几批被拉黑作者也不再「饿死」可见评论。 */
private const val MAX_COMMENT_PAGINATION_ITERATIONS = 20

fun FeedQueryService.getCommentAuthorId(commentId: String): String? {
    return transaction {
        PostComments.selectAll().where { PostComments.id eq commentId }.firstOrNull()?.get(PostComments.authorId)
    }
}

fun FeedQueryService.getComments(
    postId: String,
    currentUserId: String,
    limit: Int = 50,
    before: Long? = null,
    beforeId: String? = null
): List<PostCommentResponse>? {
    val boundedLimit = limit.coerceIn(1, 100)
    return transaction {
        if (!PostVisibility.canViewInTransaction(postId, currentUserId, lockPost = false)) return@transaction null
        val blockedUserIds = SocialGraphService.blockedEitherWayUserIds(currentUserId)
        val result = mutableListOf<PostCommentResponse>()
        var cursorTime = before
        var cursorId = beforeId
        var iterations = 0
        while (result.size < boundedLimit && iterations < MAX_COMMENT_PAGINATION_ITERATIONS) {
            val batchSize = ((boundedLimit - result.size) * 3).coerceAtLeast(boundedLimit)
            val batch = (PostComments innerJoin Users)
                .selectAll()
                .where {
                    val time = cursorTime
                    val id = cursorId
                    when {
                        time == null -> PostComments.postId eq postId
                        id.isNullOrBlank() -> (PostComments.postId eq postId) and (PostComments.createdAt less time)
                        else -> (PostComments.postId eq postId) and (
                            (PostComments.createdAt less time) or
                                ((PostComments.createdAt eq time) and (PostComments.id less id))
                            )
                    }
                }
                .orderBy(PostComments.createdAt to SortOrder.DESC, PostComments.id to SortOrder.DESC)
                .limit(batchSize)
                .toList()
            if (batch.isEmpty()) break
            // 8.38：游标必须按「可见评论的 last」推进，而非 batch.last()——
            // 否则前几批全是被拉黑作者的评论时，可见评论被跳过且提前判「没有更多」
            var lastVisibleTime: Long? = null
            var lastVisibleId: String? = null
            batch.asSequence()
                .map { it.toCommentResponse(currentUserId) }
                .filter { it.author.id !in blockedUserIds }
                .take(boundedLimit - result.size)
                .forEach { resp ->
                    result.add(resp)
                    lastVisibleTime = resp.createdAt
                    lastVisibleId = resp.id
                }
            if (lastVisibleId != null && result.size < boundedLimit) {
                cursorTime = lastVisibleTime
                cursorId = lastVisibleId
            } else {
                // 本批无可保留评论或已收集满：推进到批次末尾避免死循环
                val last = batch.last()
                cursorTime = last[PostComments.createdAt]
                cursorId = last[PostComments.id]
            }
            iterations++
        }
        // 1.75：批量填充点赞数据（消除逐条 N+1）
        enrichCommentLikes(result.asReversed(), currentUserId, blockedUserIds)
    }
}

fun FeedQueryService.listPostLikers(postId: String, currentUserId: String, limit: Int = 50): List<UserResponse>? {
    val boundedLimit = limit.coerceIn(1, 100)
    return transaction {
        if (!PostVisibility.canViewInTransaction(postId, currentUserId, lockPost = false)) return@transaction null
        val blockedUserIds = SocialGraphService.blockedEitherWayUserIds(currentUserId)
        val result = mutableListOf<UserResponse>()
        var cursorTime: Long? = null
        var cursorUserId: String? = null
        var iterations = 0
        while (result.size < boundedLimit && iterations < MAX_COMMENT_PAGINATION_ITERATIONS) {
            val batchSize = ((boundedLimit - result.size) * 3).coerceAtLeast(boundedLimit)
            val base = (PostLikes innerJoin Users)
                .selectAll()
                .where { PostLikes.postId eq postId }
            val time = cursorTime
            val uid = cursorUserId
            val batch = if (time == null || uid == null) {
                base.orderBy(PostLikes.createdAt to SortOrder.DESC, PostLikes.userId to SortOrder.DESC)
                    .limit(batchSize)
                    .toList()
            } else {
                base.andWhere {
                    (PostLikes.createdAt less time) or
                        ((PostLikes.createdAt eq time) and (PostLikes.userId less uid))
                }
                    .orderBy(PostLikes.createdAt to SortOrder.DESC, PostLikes.userId to SortOrder.DESC)
                    .limit(batchSize)
                    .toList()
            }
            if (batch.isEmpty()) break
            result += batch.asSequence()
                .map { it.toPublicUser() }
                .filter { it.id !in blockedUserIds }
                .take(boundedLimit - result.size)
            val last = batch.last()
            cursorTime = last[PostLikes.createdAt]
            cursorUserId = last[PostLikes.userId]
            iterations++
        }
        result
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

private fun ResultRow.toCommentResponse(currentUserId: String): PostCommentResponse {
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

private fun FeedQueryService.enrichCommentLikes(
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
