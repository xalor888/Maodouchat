package com.maodouchat.domain.conversation

import com.maodouchat.core.model.PostDto
import com.maodouchat.core.model.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreFeedFilterPolicyTest {

    private val nowMs = 1_000_000_000L

    private fun post(
        id: String,
        authorId: String,
        content: String,
        imageUrls: List<String> = emptyList(),
        visibility: String = "PUBLIC",
        createdAt: Long = nowMs,
        isMine: Boolean = false,
    ) = PostDto(
        id = id,
        author = UserDto(id = authorId, name = "User $authorId"),
        content = content,
        imageUrls = imageUrls,
        visibility = visibility,
        createdAt = createdAt,
        isMine = isMine,
    )

    private val mine = post("p1", "me", "hello world", imageUrls = listOf("a.jpg"), isMine = true)
    private val othersRecent = post("p2", "u2", "goodbye", createdAt = nowMs - 1000)
    private val othersOld = post("p3", "u3", "hello again", createdAt = nowMs - ExploreFeedFilterPolicy.THIRTY_DAYS_MS - 1)
    private val posts = listOf(mine, othersRecent, othersOld)

    @Test
    fun `isMine from flag`() {
        assertTrue(ExploreFeedFilterPolicy.isMine(mine, "someone"))
    }

    @Test
    fun `isMine from author id`() {
        assertTrue(ExploreFeedFilterPolicy.isMine(othersRecent, "u2"))
        assertFalse(ExploreFeedFilterPolicy.isMine(othersRecent, "me"))
    }

    @Test
    fun `isMine false when neither`() {
        assertFalse(ExploreFeedFilterPolicy.isMine(othersOld, "me"))
    }

    @Test
    fun `hasMedia`() {
        assertTrue(ExploreFeedFilterPolicy.hasMedia(mine))
        assertFalse(ExploreFeedFilterPolicy.hasMedia(othersRecent))
    }

    @Test
    fun `publicRecent keeps recent public posts`() {
        assertEquals(listOf(mine, othersRecent), ExploreFeedFilterPolicy.publicRecent(posts, nowMs))
    }

    @Test
    fun `publicRecent drops non-public posts`() {
        val private = post("p4", "u4", "secret", visibility = "PRIVATE")
        assertTrue(ExploreFeedFilterPolicy.publicRecent(listOf(private), nowMs).isEmpty())
    }

    @Test
    fun `publicRecent drops posts older than thirty days`() {
        assertTrue(ExploreFeedFilterPolicy.publicRecent(listOf(othersOld), nowMs).isEmpty())
    }

    @Test
    fun `publicRecent keeps post exactly thirty days old`() {
        val boundary = post("p5", "u5", "edge", createdAt = nowMs - ExploreFeedFilterPolicy.THIRTY_DAYS_MS)
        assertEquals(listOf(boundary), ExploreFeedFilterPolicy.publicRecent(listOf(boundary), nowMs))
    }

    @Test
    fun `filterFeed chains mine media and search in order`() {
        val out = ExploreFeedFilterPolicy.filterFeed(posts, "me", showOnlyMine = true, showOnlyMedia = true, query = "hello")
        assertEquals(listOf(mine), out)
        assertEquals(posts, ExploreFeedFilterPolicy.filterFeed(posts, "me", false, false, ""))
    }

    @Test
    fun `filterMoments chains public recent media and search`() {
        val out = ExploreFeedFilterPolicy.filterMoments(posts, nowMs, showOnlyMedia = true, query = "")
        assertEquals(listOf(mine), out)
        assertEquals(
            listOf(othersRecent),
            ExploreFeedFilterPolicy.filterMoments(posts, nowMs, showOnlyMedia = false, query = "goodbye"),
        )
    }

    @Test
    fun `filterByContent returns all on blank query`() {
        assertEquals(posts, ExploreFeedFilterPolicy.filterByContent(posts, "  "))
    }

    @Test
    fun `filterByContent matches content case-insensitively`() {
        assertEquals(listOf(mine, othersOld), ExploreFeedFilterPolicy.filterByContent(posts, "HELLO"))
    }
}
