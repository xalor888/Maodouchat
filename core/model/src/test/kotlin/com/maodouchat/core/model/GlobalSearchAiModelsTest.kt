package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GlobalSearchAiModelsTest {

    @Test
    fun `ai search outcome keeps hits and copy semantics`() {
        val hit = GlobalSearchHit("c1", "m1", "Chat", "Bob", "hi", 1000L)
        val outcome = AiSearchOutcome(listOf(hit), 2, false)
        assertEquals("c1", outcome.hits[0].chatId)
        assertEquals(2, outcome.excludedChats)
        assertFalse(outcome.noContext)
        assertEquals(5, outcome.copy(excludedChats = 5).excludedChats)
    }
}
