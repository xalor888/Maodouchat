package com.maodouchat.domain.messaging

import kotlin.test.assertEquals
import kotlin.test.Test

class WarningKindTest {

    @Test
    fun `warning kinds cover all four states in order`() {
        assertEquals(
            listOf(WarningKind.CHANGED, WarningKind.UNKNOWN, WarningKind.TRUSTED, WarningKind.NONE),
            WarningKind.entries.toList(),
        )
    }

    @Test
    fun `warning kind names resolve`() {
        assertEquals(WarningKind.CHANGED, WarningKind.valueOf("CHANGED"))
        assertEquals(WarningKind.NONE, WarningKind.valueOf("NONE"))
    }
}
