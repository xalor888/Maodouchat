package com.maodouchat.core.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChatClassificationSourceTest {

    private class FixedClassificationSource(private val counts: List<CategoryCount>) : AiChatClassificationSource {
        override suspend fun classify(chatId: String): List<CategoryCount> = counts
    }

    @Test
    fun `fixed counts are returned as-is`() = runBlocking {
        val counts = listOf(
            CategoryCount(category = "待办", count = 3, confidence = 0.9),
            CategoryCount(category = "财务", count = 1, confidence = 0.7),
        )
        assertEquals(counts, FixedClassificationSource(counts).classify("chat-1"))
    }

    @Test
    fun `empty list means no classifications`() = runBlocking {
        assertTrue(FixedClassificationSource(emptyList()).classify("chat-1").isEmpty())
    }
}
