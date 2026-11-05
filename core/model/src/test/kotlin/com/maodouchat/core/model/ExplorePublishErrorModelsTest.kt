package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorePublishErrorModelsTest {

    @Test
    fun `publish error kinds cover all five outcomes`() {
        val kinds = ExplorePublishErrorKind.entries
        assertEquals(5, kinds.size)
        assertTrue(kinds.contains(ExplorePublishErrorKind.POST_RESTRICTED))
        assertTrue(kinds.contains(ExplorePublishErrorKind.MESSAGE_RESTRICTED))
        assertTrue(kinds.contains(ExplorePublishErrorKind.SUSPENDED))
        assertTrue(kinds.contains(ExplorePublishErrorKind.AUTH))
        assertTrue(kinds.contains(ExplorePublishErrorKind.FALLBACK))
    }
}
