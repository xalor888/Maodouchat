package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftRepositoryModelsTest {

    @Test
    fun `draft defaults to private with no images`() {
        val draft = ExploreDraft()
        assertEquals("", draft.composerText)
        assertEquals("PRIVATE", draft.selectedVisibility)
        assertEquals(emptyList<String>(), draft.imageUris)
    }

    @Test
    fun `copy changes only the given fields`() {
        val draft = ExploreDraft(composerText = "hi").copy(selectedVisibility = "PUBLIC")
        assertEquals("hi", draft.composerText)
        assertEquals("PUBLIC", draft.selectedVisibility)
    }
}
