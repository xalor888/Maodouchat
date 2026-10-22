package com.maodouchat.ui.screen.explore

import com.maodouchat.explore.policy.Cursor
import com.maodouchat.network.PostDto
import com.maodouchat.network.UserDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import com.maodouchat.explore.repository.FeedController
import com.maodouchat.explore.repository.FeedRepository
import com.maodouchat.explore.repository.FeedSession
import com.maodouchat.explore.repository.FeedSessionChangedException

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
            override suspend fun load(session: FeedSession, cursor: Cursor?) = Result.success(emptyList<PostDto>())
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
            override suspend fun load(session: FeedSession, cursor: Cursor?): Result<List<PostDto>> {
                current = false
                return Result.success(listOf(post))
            }
            override suspend fun publish(session: FeedSession, content: String, imageUrls: List<String>, visibility: String?) = Result.success(post)
        }

        val error = FeedController(repository).load(session).exceptionOrNull()

        assertIs<FeedSessionChangedException>(error)
    }

    @Test
    fun publishRejectsChangedSession() = runTest {
        var current = true
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = current
            override suspend fun load(session: FeedSession, cursor: Cursor?) = Result.success(emptyList<PostDto>())
            override suspend fun publish(
                session: FeedSession,
                content: String,
                imageUrls: List<String>,
                visibility: String?,
            ): Result<PostDto> {
                current = false
                return Result.success(post)
            }
        }

        val error = FeedController(repository).publish(session, "hello", emptyList(), null).exceptionOrNull()

        assertIs<FeedSessionChangedException>(error)
    }

    @Test
    fun loadPropagatesRepositoryFailure() = runTest {
        val boom = RuntimeException("boom")
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = true
            override suspend fun load(session: FeedSession, cursor: Cursor?) = Result.failure<List<PostDto>>(boom)
            override suspend fun publish(
                session: FeedSession,
                content: String,
                imageUrls: List<String>,
                visibility: String?,
            ) = Result.success(post)
        }

        val result = FeedController(repository).load(session)

        assertEquals(boom, result.exceptionOrNull())
    }

    @Test
    fun loadForwardsCursorToRepository() = runTest {
        var seen: Cursor? = null
        val session = FeedSession("owner")
        val cursor = Cursor(createdAt = 42L, postId = "post-9")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = true
            override suspend fun load(session: FeedSession, cursor: Cursor?): Result<List<PostDto>> {
                seen = cursor
                return Result.success(emptyList())
            }
            override suspend fun publish(
                session: FeedSession,
                content: String,
                imageUrls: List<String>,
                visibility: String?,
            ) = Result.success(post)
        }

        FeedController(repository).load(session, cursor)

        assertEquals(cursor, seen)
    }

    @Test
    fun publishTrimsOnlyContentEnds() = runTest {
        var content = ""
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = true
            override suspend fun load(session: FeedSession, cursor: Cursor?) = Result.success(emptyList<PostDto>())
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

        FeedController(repository).publish(session, "  a  b  ", emptyList(), null)

        assertEquals("a  b", content)
    }

    @Test
    fun publishPropagatesRepositoryFailure() = runTest {
        val boom = RuntimeException("boom")
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = true
            override suspend fun load(session: FeedSession, cursor: Cursor?) = Result.success(emptyList<PostDto>())
            override suspend fun publish(
                session: FeedSession,
                content: String,
                imageUrls: List<String>,
                visibility: String?,
            ) = Result.failure<PostDto>(boom)
        }

        val result = FeedController(repository).publish(session, "hello", emptyList(), null)

        assertEquals(boom, result.exceptionOrNull())
    }

    @Test
    fun publishForwardsImageUrlsAndVisibilityVerbatim() = runTest {
        var urls: List<String> = emptyList()
        var visibility: String? = null
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = true
            override suspend fun load(session: FeedSession, cursor: Cursor?) = Result.success(emptyList<PostDto>())
            override suspend fun publish(
                session: FeedSession,
                content: String,
                imageUrls: List<String>,
                visibilityValue: String?,
            ): Result<PostDto> {
                urls = imageUrls
                visibility = visibilityValue
                return Result.success(post)
            }
        }

        FeedController(repository).publish(session, "hello", listOf("https://x/1.jpg"), "CONTACTS")

        assertEquals(listOf("https://x/1.jpg"), urls)
        assertEquals("CONTACTS", visibility)
    }

    @Test
    fun sessionChangeOverridesLoadFailure() = runTest {
        val boom = RuntimeException("boom")
        var current = true
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = current
            override suspend fun load(session: FeedSession, cursor: Cursor?): Result<List<PostDto>> {
                current = false
                return Result.failure(boom)
            }
            override suspend fun publish(session: FeedSession, content: String, imageUrls: List<String>, visibility: String?) = Result.success(post)
        }

        val error = FeedController(repository).load(session).exceptionOrNull()

        assertIs<FeedSessionChangedException>(error)
    }

    @Test
    fun publishBlankContentStillReachesRepository() = runTest {
        var content = "untouched"
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = true
            override suspend fun load(session: FeedSession, cursor: Cursor?) = Result.success(emptyList<PostDto>())
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

        FeedController(repository).publish(session, "   ", emptyList(), null)

        assertEquals("", content)
    }

    @Test
    fun loadDefaultCursorIsNull() = runTest {
        var seen: Cursor? = Cursor(createdAt = 1L, postId = "sentinel")
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = true
            override suspend fun load(session: FeedSession, cursor: Cursor?): Result<List<PostDto>> {
                seen = cursor
                return Result.success(emptyList())
            }
            override suspend fun publish(
                session: FeedSession,
                content: String,
                imageUrls: List<String>,
                visibility: String?,
            ) = Result.success(post)
        }

        FeedController(repository).load(session)

        assertNull(seen)
    }

    @Test
    fun sessionAccessorsDelegateToRepository() = runTest {
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = session.ownerUserId == "owner"
            override suspend fun load(session: FeedSession, cursor: Cursor?) = Result.success(emptyList<PostDto>())
            override suspend fun publish(
                session: FeedSession,
                content: String,
                imageUrls: List<String>,
                visibility: String?,
            ) = Result.success(post)
        }
        val controller = FeedController(repository)

        assertEquals(session, controller.currentSession())
        assertEquals(true, controller.isCurrent(session))
        assertEquals(false, controller.isCurrent(FeedSession("other")))
    }

    @Test
    fun publishReturnsCreatedPostOnSuccess() = runTest {
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = true
            override suspend fun load(session: FeedSession, cursor: Cursor?) = Result.success(emptyList<PostDto>())
            override suspend fun publish(
                session: FeedSession,
                content: String,
                imageUrls: List<String>,
                visibility: String?,
            ) = Result.success(post)
        }

        val result = FeedController(repository).publish(session, "hello", emptyList(), null)

        assertEquals(post, result.getOrNull())
    }

    @Test
    fun loadRejectsStaleSessionEvenOnSuccess() = runTest {
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = false
            override suspend fun load(session: FeedSession, cursor: Cursor?) = Result.success(listOf(post))
            override suspend fun publish(
                session: FeedSession,
                content: String,
                imageUrls: List<String>,
                visibility: String?,
            ) = Result.success(post)
        }

        val error = FeedController(repository).load(session).exceptionOrNull()

        assertIs<FeedSessionChangedException>(error)
    }

    @Test
    fun currentSessionNullPassthrough() = runTest {
        val repository = object : FeedRepository {
            override fun currentSession(): FeedSession? = null
            override fun isCurrent(session: FeedSession) = true
            override suspend fun load(session: FeedSession, cursor: Cursor?) = Result.success(emptyList<PostDto>())
            override suspend fun publish(
                session: FeedSession,
                content: String,
                imageUrls: List<String>,
                visibility: String?,
            ) = Result.success(post)
        }

        assertNull(FeedController(repository).currentSession())
    }

    @Test
    fun publishForwardsNullVisibilityVerbatim() = runTest {
        var visibility: String? = "sentinel"
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = true
            override suspend fun load(session: FeedSession, cursor: Cursor?) = Result.success(emptyList<PostDto>())
            override suspend fun publish(
                session: FeedSession,
                content: String,
                imageUrls: List<String>,
                visibilityValue: String?,
            ): Result<PostDto> {
                visibility = visibilityValue
                return Result.success(post)
            }
        }

        FeedController(repository).publish(session, "hello", emptyList(), null)

        assertNull(visibility)
    }

    @Test
    fun publishDoesNotTrimNonBreakingSpace() = runTest {
        var content = ""
        val session = FeedSession("owner")
        val repository = object : FeedRepository {
            override fun currentSession() = session
            override fun isCurrent(session: FeedSession) = true
            override suspend fun load(session: FeedSession, cursor: Cursor?) = Result.success(emptyList<PostDto>())
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

        // trim 只去 <= ' ' 的字符：U+00A0 留下，钉住该语义
        val nbsp = "\u00A0"
        FeedController(repository).publish(session, "${nbsp}hi${nbsp}", emptyList(), null)

        assertEquals("${nbsp}hi${nbsp}", content)
    }
}
