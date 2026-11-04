package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PreviewKindTest {

    @Test
    fun `all eight kinds exist`() {
        assertEquals(8, PreviewKind.entries.size)
    }

    @Test
    fun `kinds stay apart`() {
        assertNotEquals(PreviewKind.TEXT, PreviewKind.IMAGE)
        assertNotEquals(PreviewKind.VOICE, PreviewKind.GENERIC)
    }
}
