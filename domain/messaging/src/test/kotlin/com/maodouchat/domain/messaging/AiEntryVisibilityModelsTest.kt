package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AiEntryVisibilityModelsTest {

    @Test
    fun `composer sections keep their order`() {
        assertEquals(
            listOf(ComposerSection.DRAFT, ComposerSection.CHAT, ComposerSection.SETTINGS),
            ComposerSection.entries
        )
    }
}
