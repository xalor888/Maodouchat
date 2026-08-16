package com.maodouchat.slim

import org.junit.Assert.assertEquals
import org.junit.Test

class StickerNamePolicyTest {

    @Test
    fun sanitizePackId_keepsExpectedCharactersAndLimitsLength() {
        assertEquals("cute-pack_01", StickerNamePolicy.sanitizePackId(" cute-pack_01 "))
        assertEquals("a".repeat(40), StickerNamePolicy.sanitizePackId("a".repeat(80)))
    }

    @Test
    fun sanitizePackId_rejectsDirectoryReferences() {
        assertEquals("", StickerNamePolicy.sanitizePackId("."))
        assertEquals("", StickerNamePolicy.sanitizePackId(".."))
        assertEquals("abc", StickerNamePolicy.sanitizePackId("../abc"))
    }

    @Test
    fun sanitizeFileName_keepsExtensionsAndLimitsLength() {
        assertEquals("cat01.webp", StickerNamePolicy.sanitizeFileName(" cat01.webp "))
        assertEquals("a".repeat(80), StickerNamePolicy.sanitizeFileName("a".repeat(120)))
    }

    @Test
    fun sanitizeFileName_rejectsDirectoryReferences() {
        assertEquals("", StickerNamePolicy.sanitizeFileName("."))
        assertEquals("", StickerNamePolicy.sanitizeFileName(".."))
        assertEquals("..cat.webp", StickerNamePolicy.sanitizeFileName("../cat.webp"))
    }
}
