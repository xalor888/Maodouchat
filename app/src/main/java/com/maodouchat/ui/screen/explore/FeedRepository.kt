package com.maodouchat.ui.screen.explore

import android.app.Application
import com.maodouchat.network.ApiService
import com.maodouchat.network.PostDto
import com.maodouchat.network.TokenManager

data class FeedSession(val ownerUserId: String)

interface FeedRepository {
    fun currentSession(): FeedSession?
    fun isCurrent(session: FeedSession): Boolean
    suspend fun load(session: FeedSession, cursor: ExploreFeedPolicy.Cursor? = null): Result<List<PostDto>>
    suspend fun publish(
        session: FeedSession,
        content: String,
        imageUrls: List<String>,
        visibility: String?,
    ): Result<PostDto>
}

class FeedController(private val repository: FeedRepository) {
    fun currentSession(): FeedSession? = repository.currentSession()
    fun isCurrent(session: FeedSession): Boolean = repository.isCurrent(session)

    suspend fun load(session: FeedSession, cursor: ExploreFeedPolicy.Cursor? = null): Result<List<PostDto>> {
        val result = repository.load(session, cursor)
        return if (repository.isCurrent(session)) result else Result.failure(FeedSessionChangedException())
    }

    suspend fun publish(
        session: FeedSession,
        content: String,
        imageUrls: List<String>,
        visibility: String?,
    ): Result<PostDto> {
        val result = repository.publish(session, content.trim(), imageUrls, visibility)
        return if (repository.isCurrent(session)) result else Result.failure(FeedSessionChangedException())
    }
}

class FeedSessionChangedException : IllegalStateException("Feed session changed")

internal class AndroidFeedRepository(application: Application) : FeedRepository {
    private val tokenManager = TokenManager.getInstance(application)

    override fun currentSession(): FeedSession? = tokenManager.getUserId().orEmpty()
        .takeIf(String::isNotBlank)
        ?.let(::FeedSession)

    override fun isCurrent(session: FeedSession): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = session.ownerUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

    override suspend fun load(
        session: FeedSession,
        cursor: ExploreFeedPolicy.Cursor?,
    ): Result<List<PostDto>> = withToken(session) { token ->
        ApiService.getPosts(token, before = cursor?.createdAt, beforeId = cursor?.postId)
    }

    override suspend fun publish(
        session: FeedSession,
        content: String,
        imageUrls: List<String>,
        visibility: String?,
    ): Result<PostDto> = withToken(session) { token ->
        ApiService.createPost(token, content, imageUrls, visibility)
    }

    private suspend fun <T> withToken(
        session: FeedSession,
        block: suspend (String) -> Result<T>,
    ): Result<T> {
        if (!isCurrent(session)) return Result.failure(FeedSessionChangedException())
        val token = tokenManager.getToken().orEmpty()
        if (token.isBlank()) return Result.failure(FeedSessionChangedException())
        return block(token)
    }
}
