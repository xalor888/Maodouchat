package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolSpecModelsTest {

    @Test
    fun `parameter carries type and description`() {
        val p = Parameter("string", "Target user id")
        assertEquals("string", p.type)
        assertEquals("Target user id", p.description)
        assertNull(p.enumValues)
    }

    @Test
    fun `parameter enum values are kept when given`() {
        val p = Parameter("string", "Mode", listOf("a", "b"))
        assertEquals(listOf("a", "b"), p.enumValues)
    }
}
