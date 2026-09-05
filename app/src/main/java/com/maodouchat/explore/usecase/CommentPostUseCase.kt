package com.maodouchat.explore.usecase

import com.maodouchat.explore.repository.PostMutationRepository
import com.maodouchat.network.PostCommentDto
import com.maodouchat.network.ReportResponse
import com.maodouchat.network.api.SocialApi
import com.maodouchat.network.api.SocialApiClient

class CommentPostUseCase(
    private val mutationRepository: PostMutationRepository,
    private val socialApi: SocialApi = SocialApiClient
) {
    suspend fun loadComments(
        token: String,
        postId: String,
        limit: Int = 50,
        before: Long? = null,
        beforeId: String? = null
    ): Result<List<PostCommentDto>> =
        socialApi.getPostComments(token, postId, limit, before, beforeId)

    suspend fun sendComment(
        ownerUserId: String,
        token: String,
        postId: String,
        content: String,
        replyToCommentId: String? = null
    ): Result<PostCommentDto> =
        mutationRepository.addComment(ownerUserId, token, postId, content, replyToCommentId)

    suspend fun editComment(
        ownerUserId: String,
        token: String,
        postId: String,
        commentId: String,
        newContent: String
    ): Result<PostCommentDto> =
        mutationRepository.editComment(ownerUserId, token, postId, commentId, newContent)

    suspend fun deleteComment(
        ownerUserId: String,
        token: String,
        postId: String,
        comment: PostCommentDto,
        originalIndex: Int = 0
    ): Result<Unit> =
        mutationRepository.deleteComment(ownerUserId, token, postId, comment, originalIndex)

    suspend fun reportComment(
        ownerUserId: String,
        token: String,
        commentId: String,
        reason: String,
        description: String? = null
    ): Result<ReportResponse> =
        mutationRepository.reportComment(ownerUserId, token, commentId, reason, description)
}
