package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class StickerPackLabelTest {
    @Test
    fun `known pack keys map to their label kinds`() {
        assertEquals(StickerPackLabel.Mood, stickerPackLabelKind("mood"))
        assertEquals(StickerPackLabel.Gesture, stickerPackLabelKind("gesture"))
        assertEquals(StickerPackLabel.Party, stickerPackLabelKind("party"))
    }

    @Test
    fun `unknown keys fall back to raw key`() {
        assertEquals(StickerPackLabel.RawKey, stickerPackLabelKind("custom"))
        assertEquals(StickerPackLabel.RawKey, stickerPackLabelKind(""))
    }
}
