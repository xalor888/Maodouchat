package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalSearchModelsTest {

    @Test
    fun `mode entries keep declaration order`() {
        assertEquals(
            listOf(GlobalSearchMode.KEYWORD, GlobalSearchMode.AI),
            GlobalSearchMode.entries.toList()
        )
    }
}
