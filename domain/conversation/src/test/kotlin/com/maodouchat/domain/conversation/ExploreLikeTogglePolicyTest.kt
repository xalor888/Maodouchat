package com.maodouchat.domain.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreLikeTogglePolicyTest {

    @Test
    fun `toggle likes from zero`() {
        val out = ExploreLikeTogglePolicy.toggle(0, false)
        assertEquals(true, out.likedByMe)
        assertEquals(1, out.likeCount)
    }

    @Test
    fun `toggle likes from a positive count`() {
        val out = ExploreLikeTogglePolicy.toggle(4, false)
        assertEquals(true, out.likedByMe)
        assertEquals(5, out.likeCount)
    }

    @Test
    fun `toggle unlikes and decrements`() {
        val out = ExploreLikeTogglePolicy.toggle(5, true)
        assertEquals(false, out.likedByMe)
        assertEquals(4, out.likeCount)
    }

    @Test
    fun `toggle unlikes at zero stays at zero`() {
        val out = ExploreLikeTogglePolicy.toggle(0, true)
        assertEquals(false, out.likedByMe)
        assertEquals(0, out.likeCount)
    }

    @Test
    fun `toggle twice returns to original state`() {
        val first = ExploreLikeTogglePolicy.toggle(3, false)
        val second = ExploreLikeTogglePolicy.toggle(first.likeCount, first.likedByMe)
        assertEquals(false, second.likedByMe)
        assertEquals(3, second.likeCount)
    }
}
