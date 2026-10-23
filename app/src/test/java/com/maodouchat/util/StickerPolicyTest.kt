package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerPolicyTest {
    @Test
    fun `pushRecent moves to front and dedupes`() {
        val next = StickerPolicy.pushRecent(listOf("A", "B", "C"), "B")
        assertEquals(listOf("B", "A", "C"), next)
    }

    @Test
    fun `pushRecent respects max`() {
        val base = (1..20).map { "s$it" }
        val next = StickerPolicy.pushRecent(base, "NEW", max = 5)
        assertEquals(5, next.size)
        assertEquals("NEW", next.first())
    }

    @Test
    fun `normalizeEnabledPackIds falls back to defaults`() {
        assertEquals(
            StickerCatalog.defaultEnabledPackIds(),
            StickerPolicy.normalizeEnabledPackIds(emptyList())
        )
        assertEquals(
            listOf(StickerCatalog.PACK_MOOD),
            StickerPolicy.normalizeEnabledPackIds(listOf(StickerCatalog.PACK_MOOD, "unknown"))
        )
    }

    @Test
    fun `togglePackEnabled keeps at least one pack`() {
        val only = listOf(StickerCatalog.PACK_MOOD)
        val still = StickerPolicy.togglePackEnabled(only, StickerCatalog.PACK_MOOD, enable = false)
        assertEquals(only, still)
        val added = StickerPolicy.togglePackEnabled(only, StickerCatalog.PACK_PARTY, enable = true)
        assertTrue(StickerCatalog.PACK_PARTY in added)
        val removed = StickerPolicy.togglePackEnabled(added, StickerCatalog.PACK_MOOD, enable = false)
        assertFalse(StickerCatalog.PACK_MOOD in removed)
        assertTrue(StickerCatalog.PACK_PARTY in removed)
    }

    @Test
    fun `searchStickers matches tags and glyphs`() {
        val byTag = StickerPolicy.searchStickers("party")
        assertTrue(byTag.contains("🎉") || byTag.contains("🎊"))
        val byGlyph = StickerPolicy.searchStickers("🔥")
        assertTrue(byGlyph.contains("🔥"))
        assertTrue(StickerPolicy.searchStickers("   ").isEmpty())
    }

    @Test
    fun `pushRecent trims and ignores blank`() {
        assertEquals(listOf("B", "A"), StickerPolicy.pushRecent(listOf("A"), " B "))
        assertEquals(listOf("A"), StickerPolicy.pushRecent(listOf("A"), "   "))
    }

    @Test
    fun `pushRecent max floors at one`() {
        assertEquals(listOf("C"), StickerPolicy.pushRecent(listOf("A", "B"), "C", max = 0))
        assertEquals(listOf("C"), StickerPolicy.pushRecent(listOf("A", "B"), "C", max = -3))
    }

    @Test
    fun `normalizeEnabledPackIds trims dedupes and drops unknown`() {
        assertEquals(
            listOf(StickerCatalog.PACK_MOOD),
            StickerPolicy.normalizeEnabledPackIds(listOf("  mood ", StickerCatalog.PACK_MOOD, "nope", ""))
        )
    }

    @Test
    fun `togglePackEnabled ignores unknown pack ids`() {
        val base = listOf(StickerCatalog.PACK_MOOD)
        assertEquals(base, StickerPolicy.togglePackEnabled(base, "nope", enable = true))
        assertEquals(base, StickerPolicy.togglePackEnabled(base, "nope", enable = false))
    }

    @Test
    fun `enabledPacks follows request order`() {
        val packs = StickerPolicy.enabledPacks(listOf(StickerCatalog.PACK_PARTY, StickerCatalog.PACK_MOOD))
        assertEquals(listOf(StickerCatalog.PACK_PARTY, StickerCatalog.PACK_MOOD), packs.map { it.id })
    }
}
