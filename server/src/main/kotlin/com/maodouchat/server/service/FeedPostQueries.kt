package com.maodouchat.server.service

import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.PostLikes
import com.maodouchat.server.db.Posts
import com.maodouchat.server.model.PostResponse
import com.maodouchat.server.repository.PostJson
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
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 动态帖子查询簇：feed 流、单条/作者查询、可见性判定与批量聚合（自 FeedQueryService 迁出，零行为改动）。 */
private fun FeedQueryService.batchAggregatePostMeta(
    postIds: List<String>,
    currentUserId: String,
    blockedUserIds: Set<String> = emptySet()
): PostMeta {
    if (postIds.isEmpty()) return PostMeta(emptyMap(), emptyMap(), emptySet())
    val likeCountExpr = PostLikes.userId.count()
    val likeBase = PostLikes.select(PostLikes.postId, likeCountExpr)
        .where { PostLikes.postId inList postIds }
    val likeQuery = if (blockedUserIds.isEmpty()) {
        likeBase
    } else {
        likeBase.andWhere { PostLikes.userId notInList blockedUserIds.toList() }
    }
    val likeCounts = likeQuery
        .groupBy(PostLikes.postId)
        .associate { it[PostLikes.postId] to it[likeCountExpr].toInt() }
    val commentCountExpr = PostComments.id.count()
    val commentBase = PostComments.select(PostComments.postId, commentCountExpr)
        .where { PostComments.postId inList postIds }
    val commentQuery = if (blockedUserIds.isEmpty()) {
        commentBase
    } else {
        commentBase.andWhere { PostComments.authorId notInList blockedUserIds.toList() }
    }
    val commentCounts = commentQuery
        .groupBy(PostComments.postId)
        .associate { it[PostComments.postId] to it[commentCountExpr].toInt() }
    val likedByMe = PostLikes
        .select(PostLikes.postId)
        .where { (PostLikes.postId inList postIds) and (PostLikes.userId eq currentUserId) }
        .map { it[PostLikes.postId] }
        .toSet()
    return PostMeta(likeCounts, commentCounts, likedByMe)
}

private data class PostMeta(
    val likeCounts: Map<String, Int>,
    val commentCounts: Map<String, Int>,
    val likedByMe: Set<String>
)

private fun ResultRow.toPostResponse(currentUserId: String, meta: PostMeta): PostResponse {
    val postId = this[Posts.id]
    return PostResponse(
        id = postId,
        author = toPublicUser(),
        content = this[Posts.content],
        imageUrls = PostJson.decodeImageUrls(this[Posts.imageUrls]),
        visibility = VisibilityPolicy.normalize(this[Posts.visibility]),
        createdAt = this[Posts.createdAt],
        editedAt = this[Posts.editedAt],
        likeCount = meta.likeCounts[postId] ?: 0,
        commentCount = meta.commentCounts[postId] ?: 0,
        likedByMe = postId in meta.likedByMe,
        isMine = this[Posts.authorId] == currentUserId
    )
}

fun FeedQueryService.getFeed(
    currentUserId: String,
    limit: Int = 20,
    before: Long? = null,
    beforeId: String? = null
): List<PostResponse> {
    val boundedLimit = limit.coerceIn(1, 50) // BUG 3.2 fix: 仓库层也限制上限
    return transaction {
        val contactIds = SocialGraphService.contactIds(currentUserId)
        val blockedUserIds = SocialGraphService.blockedEitherWayUserIds(currentUserId)
        // 分批拉取：每次拉 boundedLimit*3 条候选，过滤可见后取 boundedLimit 条；
        // 若不足则继续拉取下一批，最多 20 次（避免极端分布下拉太多）
        val result = mutableListOf<PostResponse>()
        var batchBefore: Long? = before
        var batchBeforeId: String? = beforeId
        var iterations = 0
        while (result.size < boundedLimit && iterations < 20) {
            val batchSize = ((boundedLimit - result.size) * 5).coerceAtLeast(boundedLimit)
            val cursorQuery = (Posts innerJoin Users)
                .selectAll()
                .let { base ->
                    val cursor = batchBefore
                    val cursorId = batchBeforeId
                    when {
                        cursor == null -> base.where { Users.deletedAt.isNull() }
                        cursorId.isNullOrBlank() -> base.where {
                            (Posts.createdAt less cursor) and Users.deletedAt.isNull()
                        }
                        else -> base.where {
                            (Posts.createdAt less cursor) or
                                ((Posts.createdAt eq cursor) and (Posts.id less cursorId))
                        }.andWhere { Users.deletedAt.isNull() }
                    }
                }
            val visibleAuthors = contactIds + currentUserId
            val visibilityQuery = cursorQuery.andWhere {
                (Posts.authorId eq currentUserId) or
                    (Posts.visibility eq "PUBLIC") or
                    ((Posts.visibility eq "CONTACTS") and (Posts.authorId inList visibleAuthors))
            }
            val finalQuery = if (blockedUserIds.isEmpty()) {
                visibilityQuery
            } else {
                visibilityQuery.andWhere { Posts.authorId notInList blockedUserIds }
            }
            val batchQuery = finalQuery
                .orderBy(Posts.createdAt to SortOrder.DESC, Posts.id to SortOrder.DESC)
                .limit(batchSize)

            val batch = batchQuery.toList()
            if (batch.isEmpty()) break

            // 先过滤可见，再批量聚合 meta（整个 batch 一次性 SQL 聚合）
            val visibleRows = batch.filter { it.isPostVisibleTo(currentUserId, contactIds, blockedUserIds) }
            val visibleIds = visibleRows.map { it[Posts.id] }
            val meta = batchAggregatePostMeta(visibleIds, currentUserId, blockedUserIds)

            for (row in visibleRows) {
                if (result.size >= boundedLimit) break
                result.add(row.toPostResponse(currentUserId, meta))
            }
            val last = batch.last()
            batchBefore = last[Posts.createdAt]
            batchBeforeId = last[Posts.id]
            iterations++
        }
        result
    }
}

fun FeedQueryService.getPostById(postId: String, currentUserId: String): PostResponse? {
    return transaction {
        val contactIds = SocialGraphService.contactIds(currentUserId)
        val blockedUserIds = SocialGraphService.blockedEitherWayUserIds(currentUserId)
        val row = (Posts innerJoin Users)
            .selectAll()
            .where { Posts.id eq postId }
            .firstOrNull()
            ?.takeIf { it.isPostVisibleTo(currentUserId, contactIds, blockedUserIds) } ?: return@transaction null
        // 单条路径同样走聚合路径（1 条 IN 查询比 3 条 count()/empty() 快）
        val meta = batchAggregatePostMeta(listOf(postId), currentUserId, blockedUserIds)
        row.toPostResponse(currentUserId, meta)
    }
}

fun FeedQueryService.isAuthor(postId: String, userId: String): Boolean {
    return transaction {
        Posts.selectAll().where { Posts.id eq postId }
            .firstOrNull()
            ?.get(Posts.authorId) == userId
    }
}

fun FeedQueryService.getPostAuthorId(postId: String): String? {
    return transaction {
        Posts.selectAll().where { Posts.id eq postId }.firstOrNull()?.get(Posts.authorId)
    }
}

fun FeedQueryService.exists(postId: String): Boolean {
    return transaction {
        !Posts.selectAll().where { Posts.id eq postId }.empty()
    }
}

fun FeedQueryService.getPostsByAuthor(
    currentUserId: String,
    authorId: String,
    limit: Int = 30,
    before: Long? = null,
    beforeId: String? = null
): List<PostResponse> {
    return transaction {
        val contactIds = SocialGraphService.contactIds(currentUserId)
        val blockedUserIds = SocialGraphService.blockedEitherWayUserIds(currentUserId)
        if (currentUserId != authorId && authorId in blockedUserIds) return@transaction emptyList()
        val baseQuery = (Posts innerJoin Users)
            .selectAll()
            .where { Posts.authorId eq authorId }
        val query = when {
            currentUserId == authorId -> baseQuery
            authorId in contactIds -> baseQuery.andWhere {
                (Posts.visibility eq "PUBLIC") or (Posts.visibility eq "CONTACTS")
            }
            else -> baseQuery.andWhere { Posts.visibility eq "PUBLIC" }
        }
        if (before != null) {
            query.andWhere {
                if (beforeId.isNullOrBlank()) {
                    Posts.createdAt less before
                } else {
                    (Posts.createdAt less before) or
                        ((Posts.createdAt eq before) and (Posts.id less beforeId))
                }
            }
        }
        val posts = query
            .orderBy(Posts.createdAt to SortOrder.DESC, Posts.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 120))
            .toList()
        if (posts.isEmpty()) return@transaction emptyList()
        val visibleIds = posts.map { it[Posts.id] }
        val meta = batchAggregatePostMeta(visibleIds, currentUserId, blockedUserIds)
        posts.map { it.toPostResponse(currentUserId, meta) }
    }
}

fun FeedQueryService.canView(postId: String, userId: String): Boolean {
    return transaction {
        PostVisibility.canViewInTransaction(postId, userId, lockPost = false)
    }
}

private fun ResultRow.isPostVisibleTo(
    currentUserId: String,
    contactIds: Set<String>,
    blockedUserIds: Set<String>
): Boolean = VisibilityPolicy.isVisible(
    this[Posts.visibility], this[Posts.authorId], currentUserId, contactIds, blockedUserIds,
)

private fun PostJson.decodeImageUrls(value: String): List<String> {
    return try {
        PostJson.json.decodeFromString(imageUrlListSerializer, value)
    } catch (_: Exception) {
        emptyList()
    }
}
