package com.maodouchat.explore.usecase

import com.maodouchat.network.PostDto
import com.maodouchat.network.UserDto
import com.maodouchat.network.api.SocialApi
import com.maodouchat.network.api.SocialApiClient
import com.maodouchat.ui.screen.explore.ExploreFeedPolicy
import com.maodouchat.ui.screen.explore.FeedRepository
import com.maodouchat.ui.screen.explore.FeedSession

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

    suspend fun loadPostDetail(token: String, postId: String): Result<PostDto> =
        socialApi.getPost(token, postId)

    suspend fun loadLikers(token: String, postId: String, limit: Int = 50): Result<List<UserDto>> =
        socialApi.getPostLikers(token, postId, limit).map { it.likers }
}
