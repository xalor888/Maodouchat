package com.maodouchat.explore.repository

import com.maodouchat.network.CommentLikeResponse
import com.maodouchat.network.PostCommentDto
import com.maodouchat.network.PostDto
import com.maodouchat.network.ReportResponse
import com.maodouchat.network.api.SocialApi
import com.maodouchat.network.api.SocialApiClient
import com.maodouchat.security.BackgroundSessionGate

class MutationSessionChangedException : IllegalStateException("Session changed during post mutation")

interface PostMutationRepository {
    val journal: PostMutationJournal

    suspend fun togglePostLike(
        ownerUserId: String,
        token: String,
        post: PostDto,
        onOptimisticUpdate: (PostDto) -> Unit = {}
    ): Result<PostDto>

    suspend fun toggleCommentLike(
        ownerUserId: String,
        token: String,
        postId: String,
        comment: PostCommentDto,
        onOptimisticUpdate: (PostCommentDto) -> Unit = {}
    ): Result<PostCommentDto>

    suspend fun publishPost(
        ownerUserId: String,
        token: String,
        content: String,
        imageUrls: List<String>,
        visibility: String?
    ): Result<PostDto>

    suspend fun editPost(
        ownerUserId: String,
        token: String,
        postId: String,
        content: String,
        visibility: String?
    ): Result<PostDto>

    suspend fun deletePost(
        ownerUserId: String,
        token: String,
        post: PostDto,
        originalIndex: Int
    ): Result<Unit>

    suspend fun addComment(
        ownerUserId: String,
        token: String,
        postId: String,
        content: String,
        replyToCommentId: String? = null
    ): Result<PostCommentDto>

    suspend fun editComment(
        ownerUserId: String,
        token: String,
        postId: String,
        commentId: String,
        newContent: String
    ): Result<PostCommentDto>

    suspend fun deleteComment(
        ownerUserId: String,
        token: String,
        postId: String,
        comment: PostCommentDto,
        originalIndex: Int
    ): Result<Unit>

    suspend fun reportPost(
        ownerUserId: String,
        token: String,
        postId: String,
        reason: String,
        description: String? = null
    ): Result<ReportResponse>

    suspend fun reportComment(
        ownerUserId: String,
        token: String,
        commentId: String,
        reason: String,
        description: String? = null
    ): Result<ReportResponse>
}

class DefaultPostMutationRepository(
    private val socialApi: SocialApi = SocialApiClient,
    override val journal: PostMutationJournal = InMemoryPostMutationJournal(),
    private val sessionGateCheck: (expectedUserId: String, liveToken: String?) -> Boolean = { expectedUserId, liveToken ->
        BackgroundSessionGate.mayContinue(
            expectedUserId = expectedUserId,
            liveToken = liveToken,
            liveUserId = expectedUserId
        )
    }
) : PostMutationRepository {

    override suspend fun togglePostLike(
        ownerUserId: String,
        token: String,
        post: PostDto,
        onOptimisticUpdate: (PostDto) -> Unit
    ): Result<PostDto> {
        if (!sessionGateCheck(ownerUserId, token)) {
            return Result.failure(MutationSessionChangedException())
        }

        val wasLiked = post.likedByMe
        val newLiked = !wasLiked
        val newCount = (post.likeCount + if (newLiked) 1 else -1).coerceAtLeast(0)
        val optimisticPost = post.copy(likedByMe = newLiked, likeCount = newCount)

        val entry = JournalEntry(
            type = MutationType.LIKE_POST,
            targetId = post.id,
            snapshot = MutationSnapshot.PostLike(
                postId = post.id,
                wasLiked = wasLiked,
                previousCount = post.likeCount
            )
        )
        journal.record(entry)
        onOptimisticUpdate(optimisticPost)

        val apiResult = if (wasLiked) {
            socialApi.unlikePost(token, post.id)
        } else {
            socialApi.likePost(token, post.id)
        }

        return if (apiResult.isSuccess) {
            journal.commit(entry.id)
            apiResult
        } else {
            val rollbackSnapshot = journal.rollback(entry.id) as? MutationSnapshot.PostLike
            if (rollbackSnapshot != null) {
                val rolledBackPost = post.copy(
                    likedByMe = rollbackSnapshot.wasLiked,
                    likeCount = rollbackSnapshot.previousCount
                )
                onOptimisticUpdate(rolledBackPost)
            }
            apiResult
        }
    }

    override suspend fun toggleCommentLike(
        ownerUserId: String,
        token: String,
        postId: String,
        comment: PostCommentDto,
        onOptimisticUpdate: (PostCommentDto) -> Unit
    ): Result<PostCommentDto> {
        if (!sessionGateCheck(ownerUserId, token)) {
            return Result.failure(MutationSessionChangedException())
        }

        val wasLiked = comment.likedByMe
        val newLiked = !wasLiked
        val newCount = (comment.likeCount + if (newLiked) 1 else -1).coerceAtLeast(0)
        val optimisticComment = comment.copy(likedByMe = newLiked, likeCount = newCount)

        val entry = JournalEntry(
            type = MutationType.LIKE_COMMENT,
            targetId = comment.id,
            snapshot = MutationSnapshot.CommentLike(
                commentId = comment.id,
                postId = postId,
                wasLiked = wasLiked,
                previousCount = comment.likeCount
            )
        )
        journal.record(entry)
        onOptimisticUpdate(optimisticComment)

        val apiResult = if (wasLiked) {
            socialApi.unlikeComment(token, postId, comment.id)
        } else {
            socialApi.likeComment(token, postId, comment.id)
        }

        return if (apiResult.isSuccess) {
            journal.commit(entry.id)
            val resp = apiResult.getOrThrow()
            val isNowLiked = if (resp.status.isNotBlank()) resp.status == "LIKED" else newLiked
            val finalComment = comment.copy(likedByMe = isNowLiked, likeCount = resp.likeCount)
            Result.success(finalComment)
        } else {
            val rollbackSnapshot = journal.rollback(entry.id) as? MutationSnapshot.CommentLike
            if (rollbackSnapshot != null) {
                val rolledBackComment = comment.copy(
                    likedByMe = rollbackSnapshot.wasLiked,
                    likeCount = rollbackSnapshot.previousCount
                )
                onOptimisticUpdate(rolledBackComment)
            }
            Result.failure(apiResult.exceptionOrNull() ?: IllegalStateException("Failed to toggle comment like"))
        }
    }

    override suspend fun publishPost(
        ownerUserId: String,
        token: String,
        content: String,
        imageUrls: List<String>,
        visibility: String?
    ): Result<PostDto> {
        if (!sessionGateCheck(ownerUserId, token)) {
            return Result.failure(MutationSessionChangedException())
        }
        return socialApi.createPost(token, content.trim(), imageUrls, visibility)
    }

    override suspend fun editPost(
        ownerUserId: String,
        token: String,
        postId: String,
        content: String,
        visibility: String?
    ): Result<PostDto> {
        if (!sessionGateCheck(ownerUserId, token)) {
            return Result.failure(MutationSessionChangedException())
        }
        return socialApi.editPost(token, postId, content.trim(), visibility)
    }

    override suspend fun deletePost(
        ownerUserId: String,
        token: String,
        post: PostDto,
        originalIndex: Int
    ): Result<Unit> {
        if (!sessionGateCheck(ownerUserId, token)) {
            return Result.failure(MutationSessionChangedException())
        }

        val entry = JournalEntry(
            type = MutationType.DELETE_POST,
            targetId = post.id,
            snapshot = MutationSnapshot.PostDeletion(post = post, originalIndex = originalIndex)
        )
        journal.record(entry)

        val result = socialApi.deletePost(token, post.id)
        if (result.isSuccess) {
            journal.commit(entry.id)
        } else {
            journal.rollback(entry.id)
        }
        return result
    }

    override suspend fun addComment(
        ownerUserId: String,
        token: String,
        postId: String,
        content: String,
        replyToCommentId: String?
    ): Result<PostCommentDto> {
        if (!sessionGateCheck(ownerUserId, token)) {
            return Result.failure(MutationSessionChangedException())
        }
        return socialApi.createPostComment(token, postId, content.trim(), replyToCommentId)
    }

    override suspend fun editComment(
        ownerUserId: String,
        token: String,
        postId: String,
        commentId: String,
        newContent: String
    ): Result<PostCommentDto> {
        if (!sessionGateCheck(ownerUserId, token)) {
            return Result.failure(MutationSessionChangedException())
        }
        return socialApi.editPostComment(token, postId, commentId, newContent.trim())
    }

    override suspend fun deleteComment(
        ownerUserId: String,
        token: String,
        postId: String,
        comment: PostCommentDto,
        originalIndex: Int
    ): Result<Unit> {
        if (!sessionGateCheck(ownerUserId, token)) {
            return Result.failure(MutationSessionChangedException())
        }

        val entry = JournalEntry(
            type = MutationType.DELETE_COMMENT,
            targetId = comment.id,
            snapshot = MutationSnapshot.CommentDeletion(comment = comment, originalIndex = originalIndex)
        )
        journal.record(entry)

        val result = socialApi.deleteComment(token, postId, comment.id)
        if (result.isSuccess) {
            journal.commit(entry.id)
        } else {
            journal.rollback(entry.id)
        }
        return result
    }

    override suspend fun reportPost(
        ownerUserId: String,
        token: String,
        postId: String,
        reason: String,
        description: String?
    ): Result<ReportResponse> {
        if (!sessionGateCheck(ownerUserId, token)) {
            return Result.failure(MutationSessionChangedException())
        }
        return socialApi.createReport(
            token = token,
            targetType = "POST",
            targetId = postId,
            reason = reason,
            description = description
        )
    }

    override suspend fun reportComment(
        ownerUserId: String,
        token: String,
        commentId: String,
        reason: String,
        description: String?
    ): Result<ReportResponse> {
        if (!sessionGateCheck(ownerUserId, token)) {
            return Result.failure(MutationSessionChangedException())
        }
        return socialApi.createReport(
            token = token,
            targetType = "COMMENT",
            targetId = commentId,
            reason = reason,
            description = description
        )
    }
}
