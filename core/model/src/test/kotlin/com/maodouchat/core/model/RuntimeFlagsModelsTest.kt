package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeFlagsModelsTest {

    @Test
    fun `flag keeps key and default`() {
        val flag = Flag("ai_master_enabled", true)
        assertEquals("ai_master_enabled", flag.key)
        assertEquals(true, flag.default)
    }

    @Test
    fun `flag default can be false`() {
        assertEquals(false, Flag("calls_enabled", false).default)
    }
}
