package com.maodouchat.server.service

// 动态评论/点赞分页簇：评论流与点赞者列表（原 FeedCommentQueries 扩展按域拆出，零行为改动）。

import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.PostLikes
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.PostCommentResponse
import com.maodouchat.server.model.UserResponse
import com.maodouchat.server.repository.PostVisibility
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

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


/** 评论分页迭代上限：游标按可见评论推进后，前几批被拉黑作者也不再「饿死」可见评论。 */
private const val MAX_COMMENT_PAGINATION_ITERATIONS = 20
