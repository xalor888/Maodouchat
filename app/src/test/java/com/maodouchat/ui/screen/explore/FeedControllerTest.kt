package com.maodouchat.ui.screen.explore

import com.maodouchat.network.PostDto
import com.maodouchat.network.UserDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FeedControllerTest {
    private val post = PostDto(
        id = "post-1",
        author = UserDto(id = "author", name = "Author"),
        content = "content",
        createdAt = 1L,
    )

    @Test
    fun publishNormalizesContent() = runTest {
        var content = ""
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = true
            override suspend fun load(session: FeedSession, cursor: ExploreFeedPolicy.Cursor?) = Result.success(emptyList<PostDto>())
            override suspend fun publish(
                session: FeedSession,
                contentValue: String,
                imageUrls: List<String>,
                visibility: String?,
            ): Result<PostDto> {
                content = contentValue
                return Result.success(post)
            }
        }

        FeedController(repository).publish(session, "  hello  ", emptyList(), null)

        assertEquals("hello", content)
    }

    @Test
    fun resultFromChangedSessionIsRejected() = runTest {
        var current = true
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = current
            override suspend fun load(session: FeedSession, cursor: ExploreFeedPolicy.Cursor?): Result<List<PostDto>> {
                current = false
                return Result.success(listOf(post))
            }
            override suspend fun publish(session: FeedSession, content: String, imageUrls: List<String>, visibility: String?) = Result.success(post)
        }

        val error = FeedController(repository).load(session).exceptionOrNull()

        assertIs<FeedSessionChangedException>(error)
    }
}
