package com.maodouchat.domain.conversation

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.Test

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
