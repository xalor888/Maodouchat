package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PostVisibilityModelsTest {

    @Test
    fun `fromString maps known tiers`() {
        assertEquals(PostVisibilityTier.PUBLIC, PostVisibility.fromString("PUBLIC"))
        assertEquals(PostVisibilityTier.CONTACTS, PostVisibility.fromString("CONTACTS"))
        assertEquals(PostVisibilityTier.CONTACTS, PostVisibility.fromString("FRIENDS"))
        assertEquals(PostVisibilityTier.PRIVATE, PostVisibility.fromString("PRIVATE"))
    }

    @Test
    fun `fromString fails closed to private`() {
        assertEquals(PostVisibilityTier.PRIVATE, PostVisibility.fromString("UNKNOWN"))
        assertEquals(PostVisibilityTier.PRIVATE, PostVisibility.fromString(null))
        assertEquals(PostVisibilityTier.PRIVATE, PostVisibility.fromString("  "))
    }

    @Test
    fun `typealias resolves to the same tier`() {
        assertEquals(PostVisibilityTier.PUBLIC, PostVisibility.PUBLIC)
    }
}
