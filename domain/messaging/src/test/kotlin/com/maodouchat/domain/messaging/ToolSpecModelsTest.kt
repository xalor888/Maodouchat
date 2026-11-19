package com.maodouchat.domain.messaging

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

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
