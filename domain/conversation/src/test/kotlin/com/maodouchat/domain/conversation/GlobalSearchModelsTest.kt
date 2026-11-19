package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalSearchModelsTest {

    @Test
    fun `mode entries keep declaration order`() {
        assertEquals(
            listOf(GlobalSearchMode.KEYWORD, GlobalSearchMode.AI),
            GlobalSearchMode.entries.toList()
        )
    }

    @Test
    fun `hit defaults message type to TEXT`() {
        val hit = GlobalSearchHit("c1", "m1", "chat", "sender", "hi", 1000L)
        assertEquals("TEXT", hit.messageType)
        assertEquals(null, hit.semanticScore)
    }
}
