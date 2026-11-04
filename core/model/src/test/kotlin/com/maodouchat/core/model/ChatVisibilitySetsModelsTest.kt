package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatVisibilitySetsModelsTest {

    @Test
    fun `redaction sets keep locked and secret apart`() {
        val sets = RedactionSets(locked = setOf("chat-1"), secret = setOf("chat-2"))
        assertEquals(setOf("chat-1"), sets.locked)
        assertEquals(setOf("chat-2"), sets.secret)
    }

    @Test
    fun `redaction sets allow empty sides`() {
        val sets = RedactionSets(locked = emptySet(), secret = emptySet())
        assertTrue(sets.locked.isEmpty())
        assertTrue(sets.secret.isEmpty())
    }

    @Test
    fun `redaction sets do not swap sides`() {
        val sets = RedactionSets(locked = setOf("a", "b"), secret = setOf("c"))
        assertTrue("a" in sets.locked && "b" in sets.locked)
        assertEquals(setOf("c"), sets.secret)
    }
}
