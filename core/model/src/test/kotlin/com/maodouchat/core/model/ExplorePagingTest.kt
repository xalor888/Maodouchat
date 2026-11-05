package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorePagingTest {

    @Test
    fun `page sizes are positive and comments page is larger`() {
        assertEquals(50, ExplorePaging.COMMENTS_PAGE_SIZE)
        assertEquals(40, ExplorePaging.FEED_PAGE_SIZE)
    }
}
