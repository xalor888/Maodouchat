package com.maodouchat.server.repository

import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.Posts
import com.maodouchat.server.model.PostCommentResponse
import com.maodouchat.server.model.PostResponse
import com.maodouchat.server.model.UserResponse
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * B10：动态领域门面。写命令（[PostCommandService]）、读查询（[FeedQueryService]）与
 * 点赞交互（[PostInteractionService]）各自独立，本类仅组合依赖并保留原公开 API。
 */
class PostRepository {
    private val feedQueryService = FeedQueryService()
    private val postCommandService = PostCommandService(feedQueryService)
    private val postInteractionService = PostInteractionService()

    // ── 写命令 ──────────────────────────────────────────
    fun createPost(authorId: String, content: String, imageUrls: List<String>, visibility: String): PostResponse =
        postCommandService.createPost(authorId, content, imageUrls, visibility)

    fun deletePost(postId: String, requesterId: String): Boolean =
        postCommandService.deletePost(postId, requesterId)

    fun updatePost(postId: String, requesterId: String, content: String, visibility: String?): PostResponse? =
        postCommandService.updatePost(postId, requesterId, content, visibility)

    fun deletePostForModeration(postId: String): Boolean =
        postCommandService.deletePostForModeration(postId)

    fun deleteCommentForModeration(commentId: String): Boolean =
        postCommandService.deleteCommentForModeration(commentId)

    fun deleteCommentForUser(postId: String, commentId: String, userId: String): Boolean =
        postCommandService.deleteCommentForUser(postId, commentId, userId)

    fun updateCommentForUser(
        commentId: String,
        postId: String,
        userId: String,
        content: String,
    ): PostCommentResponse? = postCommandService.updateCommentForUser(commentId, postId, userId, content)

    fun findPostIdByImageFilename(filename: String): String? =
        postCommandService.findPostIdByImageFilename(filename)

    fun deleteUnclaimedPostImage(filename: String, userId: String): Boolean =
        postCommandService.deleteUnclaimedPostImage(filename, userId)

    fun allReferencedImageFilenames(): Set<String> =
        postCommandService.allReferencedImageFilenames()

    fun deleteStaleUnreferencedImages(olderThan: Long): Int =
        postCommandService.deleteStaleUnreferencedImages(olderThan)

    fun deleteAllPostsForAuthor(authorId: String): Int =
        postCommandService.deleteAllPostsForAuthor(authorId)

    // ── 读查询 ──────────────────────────────────────────
    fun getFeed(currentUserId: String, limit: Int = 20, before: Long? = null, beforeId: String? = null): List<PostResponse> =
        feedQueryService.getFeed(currentUserId, limit, before, beforeId)

    fun getPostById(postId: String, currentUserId: String): PostResponse? =
        feedQueryService.getPostById(postId, currentUserId)

    fun getPostsByAuthor(currentUserId: String, authorId: String, limit: Int = 30, before: Long? = null, beforeId: String? = null): List<PostResponse> =
        feedQueryService.getPostsByAuthor(currentUserId, authorId, limit, before, beforeId)

    fun getComments(postId: String, currentUserId: String, limit: Int = 50, before: Long? = null, beforeId: String? = null): List<PostCommentResponse>? =
        feedQueryService.getComments(postId, currentUserId, limit, before, beforeId)

    fun listPostLikers(postId: String, currentUserId: String, limit: Int = 50): List<UserResponse>? =
        feedQueryService.listPostLikers(postId, currentUserId, limit)

    fun getComment(commentId: String, currentUserId: String): PostCommentResponse? =
        feedQueryService.getComment(commentId, currentUserId)

    fun canView(postId: String, userId: String): Boolean = feedQueryService.canView(postId, userId)
    fun isAuthor(postId: String, userId: String): Boolean = feedQueryService.isAuthor(postId, userId)
    fun getPostAuthorId(postId: String): String? = feedQueryService.getPostAuthorId(postId)
    fun getCommentAuthorId(commentId: String): String? = feedQueryService.getCommentAuthorId(commentId)
    fun exists(postId: String): Boolean = feedQueryService.exists(postId)

    // ── 点赞交互 ────────────────────────────────────────
    fun likePost(postId: String, userId: String): Boolean = postInteractionService.likePost(postId, userId)
    fun hasLiked(postId: String, userId: String): Boolean = postInteractionService.hasLiked(postId, userId)
    fun unlikePost(postId: String, userId: String): Boolean = postInteractionService.unlikePost(postId, userId)
    fun likeComment(postId: String, commentId: String, userId: String): Pair<Int, Boolean> = postInteractionService.likeComment(postId, commentId, userId)
    fun unlikeComment(postId: String, commentId: String, userId: String): Int = postInteractionService.unlikeComment(postId, commentId, userId)
    fun commentLikeCount(commentId: String): Int = postInteractionService.commentLikeCount(commentId)
    fun purgeOrphanedCommentLikes(): Int = postInteractionService.purgeOrphanedCommentLikes()

    // ── 评论创建（跨读/写）──────────────────────────────
    fun addComment(postId: String, authorId: String, content: String, replyToId: String? = null): PostCommentResponse? {
        return transaction {
            if (!PostVisibility.canViewInTransaction(postId, authorId, lockPost = true)) return@transaction null
            val parentExists = replyToId.isNullOrBlank() ||
                PostComments.selectAll()
                    .where { (PostComments.id eq replyToId) and (PostComments.postId eq postId) }
                    .limit(1).empty().not()
            if (!parentExists) return@transaction null
            val commentId = "pc_${UUID.randomUUID()}"
            val now = System.currentTimeMillis()
            PostComments.insert {
                it[id] = commentId
                it[PostComments.postId] = postId
                it[PostComments.authorId] = authorId
                it[PostComments.content] = content
                it[createdAt] = now
                it[parentId] = replyToId?.takeIf(String::isNotBlank)
            }
            feedQueryService.getCommentById(commentId, authorId)
        }
    }
}
