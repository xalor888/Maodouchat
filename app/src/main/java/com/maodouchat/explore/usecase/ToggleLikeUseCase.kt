package com.maodouchat.explore.usecase

import com.maodouchat.explore.repository.PostMutationRepository
import com.maodouchat.network.PostCommentDto
import com.maodouchat.network.PostDto

class ToggleLikeUseCase(
    private val mutationRepository: PostMutationRepository
) {
    suspend fun togglePostLike(
        ownerUserId: String,
        token: String,
        post: PostDto,
        onOptimisticUpdate: (PostDto) -> Unit = {}
    ): Result<PostDto> =
        mutationRepository.togglePostLike(ownerUserId, token, post, onOptimisticUpdate)

    suspend fun toggleCommentLike(
        ownerUserId: String,
        token: String,
        postId: String,
        comment: PostCommentDto,
        onOptimisticUpdate: (PostCommentDto) -> Unit = {}
    ): Result<PostCommentDto> =
        mutationRepository.toggleCommentLike(ownerUserId, token, postId, comment, onOptimisticUpdate)
}
