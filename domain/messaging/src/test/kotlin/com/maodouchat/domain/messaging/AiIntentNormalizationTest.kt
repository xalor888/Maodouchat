package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AiIntentNormalizationTest {

    @Test
    fun legalTonesPassThrough() {
        for (tone in listOf("natural", "friendly", "formal", "concise", "warm", "humorous", "direct", "empathetic", "encouraging")) {
            assertEquals(tone, normalizeAiReplyTone(tone), tone)
        }
    }

    @Test
    fun toneIsTrimmedAndLowercased() {
        assertEquals("warm", normalizeAiReplyTone(" Warm "))
        assertEquals("formal", normalizeAiReplyTone("FORMAL"))
    }

    @Test
    fun illegalToneFallsBackToFriendly() {
        assertEquals("friendly", normalizeAiReplyTone("bogus"))
        assertEquals("friendly", normalizeAiReplyTone(""))
        assertEquals("friendly", normalizeAiReplyTone("   "))
    }

    @Test
    fun legalModesPassThrough() {
        for (mode in listOf("answer", "summary", "decisions", "tasks", "timeline", "risks")) {
            assertEquals(mode, resolveGroupAiMode(mode, "随便什么问题"), mode)
        }
    }

    @Test
    fun modeIsTrimmedAndLowercased() {
        assertEquals("summary", resolveGroupAiMode(" Summary ", "随便什么问题"))
        assertEquals("tasks", resolveGroupAiMode("TASKS", "随便什么问题"))
    }

    @Test
    fun missingOrIllegalModeIsInferredFromQuery() {
        val cases = listOf(
            Triple(null, "总结一下今天的讨论", "summary"),
            Triple("bogus", "决策一下选哪个方案", "decisions"),
            Triple("", "待办事项有哪些", "tasks"),
            Triple("  ", "时间线梳理", "timeline"),
            Triple(null, "风险有哪些", "risks"),
            Triple(null, "随便聊聊", "answer"),
            // 关键词只在句首才推断：句中出现“风险”仍回退 answer。
            Triple(null, "有什么风险", "answer"),
        )
        for ((mode, query, expected) in cases) {
            assertEquals(expected, resolveGroupAiMode(mode, query), "$mode / $query")
        }
    }
}
