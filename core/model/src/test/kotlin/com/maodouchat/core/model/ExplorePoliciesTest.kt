package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorePoliciesTest {
    private val author = UserDto(id = "u1", name = "Ada", avatar = null, email = "a@x.com", isOnline = true, status = "")

    private fun post(id: String = "p1", liked: Boolean = false, likes: Int = 3) = PostDto(
        id = id,
        author = author,
        content = "hello",
        createdAt = 1L,
        likeCount = likes,
        likedByMe = liked,
    )

    @Test
    fun toggleLikeFlipsStateAndCount() {
        val toggle = ExploreFeedPolicy.toggleLike(post(liked = false, likes = 3))
        assertTrue(toggle.willLike)
        assertTrue(toggle.optimistic.likedByMe)
        assertEquals(4, toggle.optimistic.likeCount)
        assertFalse(toggle.previous.likedByMe)
    }

    @Test
    fun formatCountCompactsThousands() {
        assertEquals("0", ExploreFeedPolicy.formatCount(0))
        assertEquals("999", ExploreFeedPolicy.formatCount(999))
        assertEquals("1.2K", ExploreFeedPolicy.formatCount(1200))
    }

    @Test
    fun canStartLoadMoreGuardsFlags() {
        assertTrue(ExploreFeedPolicy.canStartLoadMore(false, false, true, 2))
        assertFalse(ExploreFeedPolicy.canStartLoadMore(true, false, true, 2))
        assertFalse(ExploreFeedPolicy.canStartLoadMore(false, false, false, 2))
        assertFalse(ExploreFeedPolicy.canStartLoadMore(false, false, true, 0))
    }

    @Test
    fun privatePostHiddenFromOthers() {
        val privatePost = post().copy(visibility = "PRIVATE")
        assertFalse(PostVisibilityPolicy.isVisible(privatePost, viewerUserId = "u2"))
        assertTrue(PostVisibilityPolicy.isVisible(privatePost, viewerUserId = "u1"))
    }

    @Test
    fun journalRecordAndRollbackReturnsSnapshot() {
        val journal = InMemoryPostMutationJournal()
        val entry = JournalEntry(
            type = MutationType.LIKE_POST,
            targetId = "p1",
            snapshot = MutationSnapshot.PostLike(postId = "p1", wasLiked = false, previousCount = 3),
        )
        journal.record(entry)
        assertEquals(1, journal.getPendingEntries().size)
        val snapshot = journal.rollback(entry.id) as MutationSnapshot.PostLike
        assertEquals(3, snapshot.previousCount)
        assertEquals(0, journal.getPendingEntries().size)
    }
}
