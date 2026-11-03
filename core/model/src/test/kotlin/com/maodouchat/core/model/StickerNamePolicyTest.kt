package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StickerNamePolicyTest {

    @Test
    fun sanitizePackId_keepsSafeCharsAndCapsLength() {
        assertEquals("cute-pack_01", StickerNamePolicy.sanitizePackId(" cute-pack_01 "))
        assertEquals("a".repeat(40), StickerNamePolicy.sanitizePackId("a".repeat(41)))
    }

    @Test
    fun sanitizePackId_rejectsDotSegments() {
        assertEquals("", StickerNamePolicy.sanitizePackId("."))
        assertEquals("", StickerNamePolicy.sanitizePackId(".."))
        assertEquals("abc", StickerNamePolicy.sanitizePackId("../abc"))
    }

    @Test
    fun sanitizeFileName_keepsDotsAndCapsLength() {
        assertEquals("cat01.webp", StickerNamePolicy.sanitizeFileName(" cat01.webp "))
        assertEquals("a.b.webp", StickerNamePolicy.sanitizeFileName("a.b.webp"))
        assertEquals("b".repeat(80), StickerNamePolicy.sanitizeFileName("b".repeat(81)))
    }

    @Test
    fun sanitizeFileName_rejectsDotOnlySegments() {
        assertEquals("", StickerNamePolicy.sanitizeFileName("."))
        assertEquals("", StickerNamePolicy.sanitizeFileName(".."))
        assertEquals("..cat.webp", StickerNamePolicy.sanitizeFileName("../cat.webp"))
    }
}
