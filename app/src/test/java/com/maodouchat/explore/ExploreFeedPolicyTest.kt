package com.maodouchat.explore

import com.maodouchat.explore.policy.Cursor
import com.maodouchat.network.PostDto
import com.maodouchat.network.UserDto
import com.maodouchat.explore.policy.ExploreFeedPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreFeedPolicyTest {
    private val author = UserDto(id = "u1", name = "Ada", avatar = null, email = "a@x.com", isOnline = true, status = "")

    private fun post(
        id: String = "p1",
        liked: Boolean = false,
        likes: Int = 3,
        comments: Int = 1
    ) = PostDto(
        id = id,
        author = author,
        content = "hello",
        createdAt = 1L,
        likeCount = likes,
        commentCount = comments,
        likedByMe = liked
    )

    @Test
    fun `like toggles up from not liked`() {
        val toggle = ExploreFeedPolicy.toggleLike(post(liked = false, likes = 3))
        assertTrue(toggle.willLike)
        assertTrue(toggle.optimistic.likedByMe)
        assertEquals(4, toggle.optimistic.likeCount)
        assertFalse(toggle.previous.likedByMe)
    }

    @Test
    fun `unlike never goes below zero`() {
        val toggle = ExploreFeedPolicy.toggleLike(post(liked = true, likes = 0))
        assertFalse(toggle.willLike)
        assertEquals(0, toggle.optimistic.likeCount)
    }

    @Test
    fun `rollback restores previous like state`() {
        val original = post(liked = false, likes = 2)
        val toggle = ExploreFeedPolicy.toggleLike(original)
        val list = listOf(toggle.optimistic, post(id = "p2"))
        val rolled = ExploreFeedPolicy.rollbackPost(list, toggle.previous)
        assertEquals(original, rolled.single { it.id == "p1" })
        assertEquals("p2", rolled[1].id)
    }

    @Test
    fun `delete and comment count helpers`() {
        val posts = listOf(post("p1", comments = 2), post("p2"))
        assertEquals(listOf(post("p2")), ExploreFeedPolicy.removePost(posts, "p1"))
        assertEquals(3, ExploreFeedPolicy.incrementCommentCount(posts, "p1").first().commentCount)
    }

    @Test
    fun `restore deleted post preserves concurrent feed changes`() {
        val deleted = post("p1")
        val current = listOf(post("new"), post("p2"))
        assertEquals(
            listOf(deleted, post("new"), post("p2")),
            ExploreFeedPolicy.restorePost(current, deleted, originalIndex = 0)
        )
        assertEquals(current, ExploreFeedPolicy.restorePost(current, post("p2"), originalIndex = 0))
    }

    @Test
    fun `image index clamps`() {
        assertEquals(0, ExploreFeedPolicy.clampImageIndex(-1, 3))
        assertEquals(2, ExploreFeedPolicy.clampImageIndex(99, 3))
        assertEquals(0, ExploreFeedPolicy.clampImageIndex(5, 0))
    }

    @Test
    fun `canStartLoadMore blocks when busy empty or exhausted`() {
        assertTrue(
            ExploreFeedPolicy.canStartLoadMore(
                isLoading = false,
                isLoadingMore = false,
                hasMore = true,
                postCount = 20
            )
        )
        assertFalse(
            ExploreFeedPolicy.canStartLoadMore(
                isLoading = true,
                isLoadingMore = false,
                hasMore = true,
                postCount = 20
            )
        )
        assertFalse(
            ExploreFeedPolicy.canStartLoadMore(
                isLoading = false,
                isLoadingMore = true,
                hasMore = true,
                postCount = 20
            )
        )
        assertFalse(
            ExploreFeedPolicy.canStartLoadMore(
                isLoading = false,
                isLoadingMore = false,
                hasMore = false,
                postCount = 20
            )
        )
        assertFalse(
            ExploreFeedPolicy.canStartLoadMore(
                isLoading = false,
                isLoadingMore = false,
                hasMore = true,
                postCount = 0
            )
        )
    }

    @Test
    fun `pagination cursor includes id tie breaker`() {
        val posts = listOf(
            post("later").copy(createdAt = 20L),
            post("same-z").copy(createdAt = 10L),
            post("same-a").copy(createdAt = 10L)
        )
        assertEquals(
            Cursor(createdAt = 10L, postId = "same-a"),
            ExploreFeedPolicy.oldestCursor(posts)
        )
    }

    @Test
    fun `missingSessionForLoadMore detects blank tokens`() {
        assertTrue(ExploreFeedPolicy.missingSessionForLoadMore(null))
        assertTrue(ExploreFeedPolicy.missingSessionForLoadMore(""))
        assertTrue(ExploreFeedPolicy.missingSessionForLoadMore("   "))
        assertFalse(ExploreFeedPolicy.missingSessionForLoadMore("tok"))
    }

    @Test
    fun `formatCount keeps small numbers and compact large ones`() {
        assertEquals("0", ExploreFeedPolicy.formatCount(0))
        assertEquals("0", ExploreFeedPolicy.formatCount(-3))
        assertEquals("999", ExploreFeedPolicy.formatCount(999))
        assertEquals("1K", ExploreFeedPolicy.formatCount(1000))
        assertEquals("1.2K", ExploreFeedPolicy.formatCount(1200))
        assertEquals("9.9K", ExploreFeedPolicy.formatCount(9900))
        assertEquals("1万", ExploreFeedPolicy.formatCount(10_000))
        assertEquals("1.3万", ExploreFeedPolicy.formatCount(13_000))
    }

    @Test
    fun `applyServerPost replaces matching id and preserves order`() {
        val posts = listOf(post("p1"), post("p2"), post("p3"))
        val server = post("p2", likes = 99, comments = 7)
        val merged = ExploreFeedPolicy.applyServerPost(posts, server)
        assertEquals(listOf("p1", "p2", "p3"), merged.map { it.id })
        assertEquals(99, merged[1].likeCount)
        assertEquals(7, merged[1].commentCount)
        assertEquals(post("p1"), merged[0])
    }

    @Test
    fun `applyServerPost with unknown id keeps list unchanged`() {
        val posts = listOf(post("p1"), post("p2"))
        assertEquals(posts, ExploreFeedPolicy.applyServerPost(posts, post("nope")))
    }

    @Test
    fun `decrementCommentCount floors at zero`() {
        val posts = listOf(post("p1", comments = 0), post("p2", comments = 2))
        val dec = ExploreFeedPolicy.decrementCommentCount(posts, "p2")
        assertEquals(1, dec.first { it.id == "p2" }.commentCount)
        assertEquals(posts, ExploreFeedPolicy.decrementCommentCount(posts, "nope"))
        val floor = ExploreFeedPolicy.decrementCommentCount(posts, "p1")
        assertEquals(0, floor.first { it.id == "p1" }.commentCount)
    }

    @Test
    fun `oldestCursor returns null for empty list`() {
        assertEquals(null, ExploreFeedPolicy.oldestCursor(emptyList()))
    }

    @Test
    fun `double toggle restores original like state`() {
        val original = post(liked = false, likes = 3)
        val once = ExploreFeedPolicy.toggleLike(original)
        val twice = ExploreFeedPolicy.toggleLike(once.optimistic)
        assertEquals(original.likedByMe, twice.optimistic.likedByMe)
        assertEquals(original.likeCount, twice.optimistic.likeCount)
        assertFalse(twice.willLike)
    }

    @Test
    fun `formatCount rounds up and drops trailing point zero`() {
        assertEquals("2K", ExploreFeedPolicy.formatCount(2000))
        assertEquals("2K", ExploreFeedPolicy.formatCount(1950))
        assertEquals("10.5万", ExploreFeedPolicy.formatCount(105_000))
        assertEquals("2万", ExploreFeedPolicy.formatCount(20_000))
        assertEquals("1000万", ExploreFeedPolicy.formatCount(10_000_000))
    }

    @Test
    fun `restorePost clamps out of range original index`() {
        val current = listOf(post("p1"))
        val gone = post("gone")
        assertEquals(
            listOf(post("p1"), gone),
            ExploreFeedPolicy.restorePost(current, gone, originalIndex = 99)
        )
        assertEquals(
            listOf(gone, post("p1")),
            ExploreFeedPolicy.restorePost(current, gone, originalIndex = -5)
        )
    }

    @Test
    fun `removePost and incrementCommentCount ignore unknown ids`() {
        val posts = listOf(post("p1", comments = 2), post("p2"))
        assertEquals(posts, ExploreFeedPolicy.removePost(posts, "nope"))
        assertEquals(posts, ExploreFeedPolicy.incrementCommentCount(posts, "nope"))
    }

    @Test
    fun `clampImageIndex keeps in range index and floors bad size`() {
        assertEquals(1, ExploreFeedPolicy.clampImageIndex(1, 3))
        assertEquals(2, ExploreFeedPolicy.clampImageIndex(2, 3))
        assertEquals(0, ExploreFeedPolicy.clampImageIndex(0, -1))
    }

    @Test
    fun `toggleLike from negative count never leaks below zero`() {
        val toggle = ExploreFeedPolicy.toggleLike(post(liked = false, likes = -2))
        assertTrue(toggle.willLike)
        assertEquals(0, toggle.optimistic.likeCount)
    }
}
