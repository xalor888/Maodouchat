package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiDraftTextPolicyTest {

    @Test
    fun `short text passes through`() {
        assertEquals("hello", AiDraftTextPolicy.truncate("hello"))
    }

    @Test
    fun `long text truncates at 4000`() {
        val text = "x".repeat(4001)
        assertEquals("x".repeat(4000), AiDraftTextPolicy.truncate(text))
    }

    @Test
    fun `exactly 4000 stays intact`() {
        val text = "y".repeat(4000)
        assertEquals(text, AiDraftTextPolicy.truncate(text))
    }

    @Test
    fun `empty text passes through`() {
        assertEquals("", AiDraftTextPolicy.truncate(""))
    }

    @Test
    fun `isFull at the boundary`() {
        assertFalse(AiDraftTextPolicy.isFull(3999))
        assertTrue(AiDraftTextPolicy.isFull(4000))
        assertTrue(AiDraftTextPolicy.isFull(4001))
    }

    @Test
    fun `append budget clamps at zero`() {
        assertEquals(4000, AiDraftTextPolicy.appendBudget(0))
        assertEquals(1, AiDraftTextPolicy.appendBudget(3999))
        assertEquals(0, AiDraftTextPolicy.appendBudget(4000))
        assertEquals(0, AiDraftTextPolicy.appendBudget(5000))
    }
}
