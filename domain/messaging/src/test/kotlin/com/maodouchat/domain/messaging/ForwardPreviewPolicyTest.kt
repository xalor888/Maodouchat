package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ForwardPreviewPolicyTest {

    @Test
    fun `previewText folds newlines and truncates to 40 chars`() {
        assertEquals("a b c", ForwardPreviewPolicy.previewText("a\nb\nc"))
        assertEquals("a".repeat(40), ForwardPreviewPolicy.previewText("a".repeat(50)))
        assertEquals("abc", ForwardPreviewPolicy.previewText("abc"))
    }

    @Test
    fun `filterNote truncates to 500 chars`() {
        assertEquals("a".repeat(500), ForwardPreviewPolicy.filterNote("a".repeat(501)))
        assertEquals("abc", ForwardPreviewPolicy.filterNote("abc"))
    }

    @Test
    fun `previewOverflowCount counts beyond three`() {
        assertEquals(0, ForwardPreviewPolicy.previewOverflowCount(0))
        assertEquals(0, ForwardPreviewPolicy.previewOverflowCount(3))
        assertEquals(2, ForwardPreviewPolicy.previewOverflowCount(5))
    }

    @Test
    fun `hiddenTargetCount counts beyond one page`() {
        assertEquals(0, ForwardPreviewPolicy.hiddenTargetCount(0))
        assertEquals(0, ForwardPreviewPolicy.hiddenTargetCount(64))
        assertEquals(6, ForwardPreviewPolicy.hiddenTargetCount(70))
    }

    @Test
    fun `constants match original literals`() {
        assertEquals(64, ForwardPreviewPolicy.PAGE_SIZE)
        assertEquals(3, ForwardPreviewPolicy.PREVIEW_COUNT)
        assertEquals(40, ForwardPreviewPolicy.PREVIEW_TEXT_LENGTH)
        assertEquals(500, ForwardPreviewPolicy.NOTE_MAX_LENGTH)
        assertEquals(6, ForwardPreviewPolicy.RECENT_CHIPS_MAX)
    }
}
