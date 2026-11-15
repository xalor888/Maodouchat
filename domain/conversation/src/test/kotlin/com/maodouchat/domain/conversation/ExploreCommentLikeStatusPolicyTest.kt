package com.maodouchat.domain.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreCommentLikeStatusPolicyTest {

    @Test
    fun `server status LIKED wins over optimistic value`() {
        assertEquals(true, ExploreCommentLikeStatusPolicy.resolve("LIKED", false))
    }

    @Test
    fun `server status UNLIKED wins over optimistic value`() {
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve("UNLIKED", true))
    }

    @Test
    fun `blank status keeps optimistic liked`() {
        assertEquals(true, ExploreCommentLikeStatusPolicy.resolve("", true))
    }

    @Test
    fun `blank status keeps optimistic unliked`() {
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve("", false))
    }

    @Test
    fun `status match is exact`() {
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve("liked", true))
    }
}
