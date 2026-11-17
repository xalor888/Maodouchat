package com.maodouchat.domain.messaging

import com.maodouchat.core.model.StickerPack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StickerTabDisplayPolicyTest {

    private fun pack(id: String, vararg stickers: String) =
        StickerPack(id = id, nameKey = id, stickers = stickers.toList())

    private val packs = listOf(pack("p1", "a", "b"), pack("p2", "c"))

    @Test
    fun `non-blank query returns search hits`() {
        assertEquals(
            listOf("x"),
            StickerTabDisplayPolicy.resolveItems("x", "p1", listOf("r"), listOf("x"), packs),
        )
    }

    @Test
    fun `recent tab returns recent stickers`() {
        assertEquals(
            listOf("r1", "r2"),
            StickerTabDisplayPolicy.resolveItems("", "recent", listOf("r1", "r2"), listOf("x"), packs),
        )
    }

    @Test
    fun `known tab returns that pack stickers`() {
        assertEquals(
            listOf("c"),
            StickerTabDisplayPolicy.resolveItems("", "p2", listOf("r"), listOf("x"), packs),
        )
    }

    @Test
    fun `unknown tab falls back to first pack`() {
        assertEquals(
            listOf("a", "b"),
            StickerTabDisplayPolicy.resolveItems("", "nope", listOf("r"), listOf("x"), packs),
        )
    }

    @Test
    fun `no packs returns empty list`() {
        assertEquals(
            emptyList<String>(),
            StickerTabDisplayPolicy.resolveItems("", "p1", listOf("r"), listOf("x"), emptyList()),
        )
    }

    @Test
    fun `blank query on recent tab ignores search hits`() {
        assertEquals(
            listOf("r"),
            StickerTabDisplayPolicy.resolveItems("  ", "recent", listOf("r"), listOf("x"), packs),
        )
    }
}
