package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
