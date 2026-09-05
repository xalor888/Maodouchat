package com.maodouchat.explore.usecase

import com.maodouchat.explore.policy.PostVisibility
import com.maodouchat.explore.policy.PostVisibilityPolicy
import com.maodouchat.explore.repository.DraftRepository
import com.maodouchat.explore.repository.MediaUploadQueue
import com.maodouchat.explore.repository.PostMutationRepository
import com.maodouchat.network.PostDto
import com.maodouchat.network.ReportResponse

sealed class PublishPostValidation {
    object Valid : PublishPostValidation()
    data class Invalid(val reason: String) : PublishPostValidation()
}

class PublishPostUseCase(
    private val mutationRepository: PostMutationRepository,
    private val draftRepository: DraftRepository,
    private val uploadQueue: MediaUploadQueue
) {
    fun validate(content: String, imageUrls: List<String>): PublishPostValidation {
        val trimmed = content.trim()
        if (trimmed.isEmpty() && imageUrls.isEmpty()) {
            return PublishPostValidation.Invalid("Post content or image cannot be empty")
        }
        if (trimmed.length > 5000) {
            return PublishPostValidation.Invalid("Post content exceeds 5000 characters")
        }
        return PublishPostValidation.Valid
    }

    suspend fun publish(
        ownerUserId: String,
        token: String,
        content: String,
        imageUrls: List<String>,
        visibility: PostVisibility = PostVisibility.PUBLIC
    ): Result<PostDto> {
        val validation = validate(content, imageUrls)
        if (validation is PublishPostValidation.Invalid) {
            return Result.failure(IllegalArgumentException(validation.reason))
        }

        val normalizedVisibility = PostVisibilityPolicy.formatForApi(visibility)
        val result = mutationRepository.publishPost(
            ownerUserId = ownerUserId,
            token = token,
            content = content,
            imageUrls = imageUrls,
            visibility = normalizedVisibility
        )

        if (result.isSuccess) {
            draftRepository.clearAllDrafts(ownerUserId)
        }
        return result
    }

    suspend fun edit(
        ownerUserId: String,
        token: String,
        postId: String,
        content: String,
        visibility: PostVisibility = PostVisibility.PUBLIC
    ): Result<PostDto> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Content cannot be empty"))
        }
        val normalizedVisibility = PostVisibilityPolicy.formatForApi(visibility)
        return mutationRepository.editPost(
            ownerUserId = ownerUserId,
            token = token,
            postId = postId,
            content = trimmed,
            visibility = normalizedVisibility
        )
    }

    suspend fun delete(
        ownerUserId: String,
        token: String,
        post: PostDto,
        originalIndex: Int = 0
    ): Result<Unit> =
        mutationRepository.deletePost(ownerUserId, token, post, originalIndex)

    suspend fun report(
        ownerUserId: String,
        token: String,
        postId: String,
        reason: String,
        description: String? = null
    ): Result<ReportResponse> =
        mutationRepository.reportPost(ownerUserId, token, postId, reason, description)
}
