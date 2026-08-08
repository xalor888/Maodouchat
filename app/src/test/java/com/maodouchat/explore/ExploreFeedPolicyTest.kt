package com.maodouchat.explore

import com.maodouchat.network.PostDto
import com.maodouchat.network.UserDto
import com.maodouchat.ui.screen.explore.ExploreFeedPolicy
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
            ExploreFeedPolicy.Cursor(createdAt = 10L, postId = "same-a"),
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
}
