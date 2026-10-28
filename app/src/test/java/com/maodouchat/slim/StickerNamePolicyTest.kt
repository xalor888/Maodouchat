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

    @Test
    fun sanitizePackId_stripsSpacesAndSpecialsInside() {
        assertEquals("abcd", StickerNamePolicy.sanitizePackId("a b/c:d"))
    }

    @Test
    fun sanitizePackId_trimsBeforeLengthCap() {
        assertEquals("a".repeat(40), StickerNamePolicy.sanitizePackId("   " + "a".repeat(50)))
    }

    @Test
    fun sanitizePackId_keepsDashUnderscoreDigits() {
        assertEquals("A-1_b2", StickerNamePolicy.sanitizePackId("A-1_b2"))
    }

    @Test
    fun sanitizeFileName_keepsMultipleDotsInside() {
        assertEquals("a..b.webp", StickerNamePolicy.sanitizeFileName("a..b.webp"))
    }

    @Test
    fun sanitizeFileName_trimsBeforeLengthCap() {
        assertEquals("b".repeat(80), StickerNamePolicy.sanitizeFileName("  " + "b".repeat(100)))
    }

}
