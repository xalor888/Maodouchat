package com.maodouchat.explore.usecase

import com.maodouchat.network.PostDto
import com.maodouchat.network.UserDto
import com.maodouchat.network.api.SocialApi
import com.maodouchat.network.api.SocialApiClient
import com.maodouchat.explore.policy.ExploreFeedPolicy
import com.maodouchat.explore.repository.FeedRepository
import com.maodouchat.explore.repository.FeedSession

class LoadFeedUseCase(
    private val feedRepository: FeedRepository,
    private val socialApi: SocialApi = SocialApiClient
) {
    suspend fun refresh(ownerUserId: String): Result<List<PostDto>> {
        val session = FeedSession(ownerUserId)
        return feedRepository.load(session, cursor = null)
    }

    suspend fun loadMore(
        ownerUserId: String,
        cursor: ExploreFeedPolicy.Cursor?
    ): Result<List<PostDto>> {
        val session = FeedSession(ownerUserId)
        return feedRepository.load(session, cursor = cursor)
    }

    suspend fun loadPostDetail(token: String? = null, postId: String): Result<PostDto> =
        socialApi.getPost(token ?: curToken(), postId)

    suspend fun loadLikers(token: String? = null, postId: String, limit: Int = 50): Result<List<UserDto>> =
        socialApi.getPostLikers(token ?: curToken(), postId, limit).map { it.likers }

    /** 未显式传令牌时取当前会话的（与 `data/repository/SessionTokens.kt` 同一约定）。 */
    private fun curToken(): String = com.maodouchat.session.CurrentSession.snapshot().token.orEmpty()
}
