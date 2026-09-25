package com.maodouchat.explore.usecase

import com.maodouchat.explore.repository.PostMutationRepository
import com.maodouchat.network.PostCommentDto
import com.maodouchat.network.PostDto

class ToggleLikeUseCase(
    private val mutationRepository: PostMutationRepository
) {
    suspend fun togglePostLike(
        ownerUserId: String,
        token: String? = null,
        post: PostDto,
        onOptimisticUpdate: (PostDto) -> Unit = {}
    ): Result<PostDto> =
        mutationRepository.togglePostLike(ownerUserId, token ?: curToken(), post, onOptimisticUpdate)

    suspend fun toggleCommentLike(
        ownerUserId: String,
        token: String? = null,
        postId: String,
        comment: PostCommentDto,
        onOptimisticUpdate: (PostCommentDto) -> Unit = {}
    ): Result<PostCommentDto> =
        mutationRepository.toggleCommentLike(ownerUserId, token ?: curToken(), postId, comment, onOptimisticUpdate)

    /** 未显式传令牌时取当前会话的（与 `data/repository/SessionTokens.kt` 同一约定）。 */
    private fun curToken(): String = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
}
