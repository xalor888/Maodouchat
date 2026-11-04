package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AttachMenuModelsTest {

    @Test
    fun `all fifteen kinds exist`() {
        assertEquals(15, AttachMenuKind.entries.size)
    }

    @Test
    fun `one-to-one-only kinds stay apart from media kinds`() {
        assertNotEquals(AttachMenuKind.VIEW_ONCE, AttachMenuKind.IMAGE)
        assertNotEquals(AttachMenuKind.LIVE_LOCATION, AttachMenuKind.LOCATION)
        assertNotEquals(AttachMenuKind.NUDGE, AttachMenuKind.SILENT)
    }

    @Test
    fun `entries keep their declared order`() {
        assertEquals(
            listOf(
                AttachMenuKind.IMAGE,
                AttachMenuKind.VIEW_ONCE,
                AttachMenuKind.SPOILER,
                AttachMenuKind.PASTE,
                AttachMenuKind.VIDEO,
                AttachMenuKind.FILE,
                AttachMenuKind.VOICE,
                AttachMenuKind.LOCATION,
                AttachMenuKind.LIVE_LOCATION,
                AttachMenuKind.SCHEDULE,
                AttachMenuKind.QUICK_PHRASES,
                AttachMenuKind.CONTACT_CARD,
                AttachMenuKind.AI,
                AttachMenuKind.SILENT,
                AttachMenuKind.NUDGE,
            ),
            AttachMenuKind.entries.toList()
        )
    }
}
