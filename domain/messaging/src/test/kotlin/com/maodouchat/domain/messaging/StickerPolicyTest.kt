package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StickerPolicyTest {

    @Test
    fun `pushRecent moves to front dedupes and truncates`() {
        assertEquals(listOf("B", "A", "C"), StickerPolicy.pushRecent(listOf("A", "B", "C"), "B"))
        assertEquals(listOf("NEW"), StickerPolicy.pushRecent(listOf("A"), "NEW", max = 1))
        assertEquals(listOf("A"), StickerPolicy.pushRecent(listOf("A"), "  "))
    }

    @Test
    fun `normalizeEnabledPackIds filters unknown and falls back to available`() {
        val available = listOf(StickerCatalog.PACK_MOOD, StickerCatalog.PACK_GESTURE)
        assertEquals(
            listOf(StickerCatalog.PACK_MOOD),
            StickerPolicy.normalizeEnabledPackIds(listOf(StickerCatalog.PACK_MOOD, "unknown"), available)
        )
        assertEquals(available, StickerPolicy.normalizeEnabledPackIds(emptyList(), available))
        assertEquals(available, StickerPolicy.normalizeEnabledPackIds(null, available))
    }

    @Test
    fun `enabledPacks keeps requested order filtered by catalog`() {
        val packs = StickerPolicy.enabledPacks(
            listOf(StickerCatalog.PACK_PARTY, StickerCatalog.PACK_MOOD)
        )
        assertEquals(listOf(StickerCatalog.PACK_PARTY, StickerCatalog.PACK_MOOD), packs.map { it.id })
        assertTrue(packs.all { it.stickers.isNotEmpty() })
    }
}
