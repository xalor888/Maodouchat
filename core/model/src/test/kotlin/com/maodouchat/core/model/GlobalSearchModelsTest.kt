package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalSearchModelsTest {

    @Test
    fun `hit defaults message type to TEXT`() {
        val hit = GlobalSearchHit("c1", "m1", "chat", "sender", "hi", 1000L)
        assertEquals("TEXT", hit.messageType)
        assertEquals(null, hit.semanticScore)
    }
}
