package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ArchiveSuggestion
import kotlin.test.Test
import kotlin.test.assertEquals

class ArchiveSuggestionDisplayPolicyTest {

    private fun suggestion(id: String) = ArchiveSuggestion(chatId = id, score = 10, reason = "quiet")

    @Test
    fun `empty stays empty`() {
        assertEquals(emptyList(), ArchiveSuggestionDisplayPolicy.preview(emptyList()))
    }

    @Test
    fun `fewer than limit passes through`() {
        val input = listOf(suggestion("c1"))
        assertEquals(input, ArchiveSuggestionDisplayPolicy.preview(input))
    }

    @Test
    fun `exactly limit passes through`() {
        val input = listOf(suggestion("c1"), suggestion("c2"), suggestion("c3"))
        assertEquals(input, ArchiveSuggestionDisplayPolicy.preview(input))
    }

    @Test
    fun `more than limit keeps first three`() {
        val input = (1..5).map { suggestion("c$it") }
        assertEquals(input.take(3), ArchiveSuggestionDisplayPolicy.preview(input))
    }
}
