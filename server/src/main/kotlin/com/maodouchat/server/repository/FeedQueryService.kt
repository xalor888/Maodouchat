package com.maodouchat.server.repository

import com.maodouchat.server.db.CommentLikes
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.PostLikes
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.PostCommentResponse
import com.maodouchat.server.model.PostResponse
import com.maodouchat.server.model.UserResponse
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

/** B10：动态 feed/详情/评论/点赞者查询子域（可见性 + 社交图 + 批量聚合）。 */
class FeedQueryService {
    private val json = PostJson.json

    private companion object {
        /** 评论分页迭代上限：游标按可见评论推进后，前几批被拉黑作者也不再「饿死」可见评论。 */
        const val MAX_COMMENT_PAGINATION_ITERATIONS = 20
    }

    private fun batchAggregatePostMeta(
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

    fun getFeed(
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

    fun getPostById(postId: String, currentUserId: String): PostResponse? {
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

    fun isAuthor(postId: String, userId: String): Boolean {
        return transaction {
            Posts.selectAll().where { Posts.id eq postId }
                .firstOrNull()
                ?.get(Posts.authorId) == userId
        }
    }

    fun getPostAuthorId(postId: String): String? {
        return transaction {
            Posts.selectAll().where { Posts.id eq postId }.firstOrNull()?.get(Posts.authorId)
        }
    }

    fun getCommentAuthorId(commentId: String): String? {
        return transaction {
            PostComments.selectAll().where { PostComments.id eq commentId }.firstOrNull()?.get(PostComments.authorId)
        }
    }

    fun exists(postId: String): Boolean {
        return transaction {
            !Posts.selectAll().where { Posts.id eq postId }.empty()
        }
    }

    fun getPostsByAuthor(
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

    fun canView(postId: String, userId: String): Boolean {
        return transaction {
            PostVisibility.canViewInTransaction(postId, userId, lockPost = false)
        }
    }

    fun getComments(
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

    fun listPostLikers(postId: String, currentUserId: String, limit: Int = 50): List<UserResponse>? {
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

    internal fun getCommentById(
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

    fun getComment(commentId: String, currentUserId: String): PostCommentResponse? {
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

    private fun enrichCommentLikes(
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

    private fun ResultRow.toPublicUser(): UserResponse {
        return UserResponse(
            id = this[Users.id],
            name = this[Users.name],
            email = "",
            avatar = this[Users.avatar],
            status = if (this[Users.showStatus]) this[Users.status] else "",
            isOnline = this[Users.showOnline] && this[Users.isOnline]
        )
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

}
