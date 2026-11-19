package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.LocalAiProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalAiProviderSelectionPolicyTest {

    private fun provider(id: String) = LocalAiProvider(
        id = id,
        name = id,
        baseUrl = "https://example.invalid",
        apiKey = "",
        model = "m",
    )

    @Test
    fun `empty list has no active provider`() {
        assertNull(LocalAiProviderSelectionPolicy.resolveActive(emptyList(), "a"))
    }

    @Test
    fun `active id selects matching provider`() {
        val list = listOf(provider("a"), provider("b"))
        assertEquals("b", LocalAiProviderSelectionPolicy.resolveActive(list, "b")?.id)
    }

    @Test
    fun `unknown active id falls back to first provider`() {
        val list = listOf(provider("a"), provider("b"))
        assertEquals("a", LocalAiProviderSelectionPolicy.resolveActive(list, "zzz")?.id)
    }

    @Test
    fun `null active id falls back to first provider`() {
        val list = listOf(provider("a"), provider("b"))
        assertEquals("a", LocalAiProviderSelectionPolicy.resolveActive(list, null)?.id)
    }

    @Test
    fun `upsert replaces same id and moves it last`() {
        val list = listOf(provider("a"), provider("b"))
        val next = LocalAiProviderSelectionPolicy.upsert(list, provider("a"))
        assertEquals(listOf("b", "a"), next.map { it.id })
    }

    @Test
    fun `upsert appends a new provider`() {
        val list = listOf(provider("a"))
        val next = LocalAiProviderSelectionPolicy.upsert(list, provider("b"))
        assertEquals(listOf("a", "b"), next.map { it.id })
    }

    @Test
    fun `delete removes only the matching id`() {
        val list = listOf(provider("a"), provider("b"), provider("c"))
        assertEquals(listOf("a", "c"), LocalAiProviderSelectionPolicy.delete(list, "b").map { it.id })
    }

    @Test
    fun `deleting the active provider falls back to first remaining id`() {
        val next = listOf(provider("b"), provider("c"))
        assertEquals("b", LocalAiProviderSelectionPolicy.activeIdAfterDelete(next, "a", "a"))
    }

    @Test
    fun `deleting the active provider with none left yields empty id`() {
        assertEquals("", LocalAiProviderSelectionPolicy.activeIdAfterDelete(emptyList(), "a", "a"))
    }

    @Test
    fun `deleting another provider keeps the active id`() {
        val next = listOf(provider("a"), provider("c"))
        assertEquals("a", LocalAiProviderSelectionPolicy.activeIdAfterDelete(next, "b", "a"))
    }

    @Test
    fun `null active id stays null after delete`() {
        val next = listOf(provider("a"))
        assertNull(LocalAiProviderSelectionPolicy.activeIdAfterDelete(next, "b", null))
    }
}
