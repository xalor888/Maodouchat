package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiProfileNarrativePolicyTest {

    @Test
    fun `stats hint formats counts days and top terms`() {
        val stats = LocalStats(messageCount = 12, activeDays = 3, topTerms = listOf("猫", "狗"))
        assertEquals("消息 12 条，活跃 3 天，主题 猫、狗", AiProfileNarrativePolicy.statsHint(stats))
    }

    @Test
    fun `stats hint with no terms keeps empty tail`() {
        val stats = LocalStats(messageCount = 0, activeDays = 0, topTerms = emptyList())
        assertEquals("消息 0 条，活跃 0 天，主题 ", AiProfileNarrativePolicy.statsHint(stats))
    }

    @Test
    fun `combine joins hint and summary with a full stop`() {
        assertEquals(
            "消息 1 条，活跃 1 天，主题 猫。对方最近常聊猫。",
            AiProfileNarrativePolicy.combineNarrative("消息 1 条，活跃 1 天，主题 猫", "对方最近常聊猫。")
        )
    }

    @Test
    fun `combine trims and caps at max narrative chars`() {
        val combined = AiProfileNarrativePolicy.combineNarrative("提示", "x".repeat(7_000))
        assertEquals(AiProfileNarrativePolicy.MAX_NARRATIVE_CHARS, combined.length)
        assertTrue(combined.startsWith("提示。"))
    }

    @Test
    fun `context and char limits`() {
        assertEquals(60, AiProfileNarrativePolicy.MAX_CONTEXT_MESSAGES)
        assertEquals(6_000, AiProfileNarrativePolicy.MAX_NARRATIVE_CHARS)
    }
}
