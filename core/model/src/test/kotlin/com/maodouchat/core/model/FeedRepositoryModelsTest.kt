package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRepositoryModelsTest {

    @Test
    fun `session keeps owner id`() {
        assertEquals("owner-1", FeedSession("owner-1").ownerUserId)
    }

    @Test
    fun `changed exception is an illegal state`() {
        assertTrue(FeedSessionChangedException() is IllegalStateException)
        assertEquals("Feed session changed", FeedSessionChangedException().message)
    }
}
