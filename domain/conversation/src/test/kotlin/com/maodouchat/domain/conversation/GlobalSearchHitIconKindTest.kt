package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalSearchHitIconKindTest {

    @Test
    fun `image gif sticker map to image`() {
        assertEquals(GlobalSearchHitIconKind.IMAGE, globalSearchHitIconKind("IMAGE", null))
        assertEquals(GlobalSearchHitIconKind.IMAGE, globalSearchHitIconKind("GIF", null))
        assertEquals(GlobalSearchHitIconKind.IMAGE, globalSearchHitIconKind("STICKER", null))
    }

    @Test
    fun `each media type maps to its own kind`() {
        assertEquals(GlobalSearchHitIconKind.VIDEO, globalSearchHitIconKind("VIDEO", null))
        assertEquals(GlobalSearchHitIconKind.FILE, globalSearchHitIconKind("FILE", null))
        assertEquals(GlobalSearchHitIconKind.VOICE, globalSearchHitIconKind("VOICE", null))
        assertEquals(GlobalSearchHitIconKind.LOCATION, globalSearchHitIconKind("LOCATION", null))
    }

    @Test
    fun `unknown type with semantic score maps to ai semantic`() {
        assertEquals(GlobalSearchHitIconKind.AI_SEMANTIC, globalSearchHitIconKind("TEXT", 0.9))
        assertEquals(GlobalSearchHitIconKind.AI_SEMANTIC, globalSearchHitIconKind("UNKNOWN", 0.1))
    }

    @Test
    fun `unknown type without score maps to text`() {
        assertEquals(GlobalSearchHitIconKind.TEXT, globalSearchHitIconKind("TEXT", null))
        assertEquals(GlobalSearchHitIconKind.TEXT, globalSearchHitIconKind("UNKNOWN", null))
    }

    @Test
    fun `known type wins over semantic score`() {
        assertEquals(GlobalSearchHitIconKind.IMAGE, globalSearchHitIconKind("IMAGE", 0.9))
        assertEquals(GlobalSearchHitIconKind.VIDEO, globalSearchHitIconKind("VIDEO", 0.9))
    }

    @Test
    fun `empty type without score maps to text`() {
        assertEquals(GlobalSearchHitIconKind.TEXT, globalSearchHitIconKind("", null))
    }
}
