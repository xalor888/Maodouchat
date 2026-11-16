package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiSemanticSearchPolicyTest {

    @Test
    fun `sender truncates at 80`() {
        val c = AiSemanticSearchPolicy.candidate("m1", "s".repeat(81), "text", 1L)
        assertEquals("s".repeat(80), c.sender)
    }

    @Test
    fun `text truncates at 700`() {
        val c = AiSemanticSearchPolicy.candidate("m1", "me", "x".repeat(701), 1L)
        assertEquals("x".repeat(700), c.text)
    }

    @Test
    fun `exact boundary values pass through`() {
        val c = AiSemanticSearchPolicy.candidate("m1", "s".repeat(80), "x".repeat(700), 1L)
        assertEquals(80, c.sender.length)
        assertEquals(700, c.text.length)
    }

    @Test
    fun `candidate limit keeps first 80`() {
        val all = (1..81).map {
            AiSemanticSearchPolicy.candidate("m$it", "me", "t", it.toLong())
        }
        assertEquals(80, AiSemanticSearchPolicy.limitCandidates(all).size)
        assertEquals("m80", AiSemanticSearchPolicy.limitCandidates(all).last().messageId)
    }

    @Test
    fun `selectResultIds drops ids outside the candidate set`() {
        val ids = AiSemanticSearchPolicy.selectResultIds(
            listOf("a", "zzz", "b"), setOf("a", "b"),
        )
        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun `selectResultIds dedupes keeping first order`() {
        val ids = AiSemanticSearchPolicy.selectResultIds(
            listOf("b", "a", "b", "a"), setOf("a", "b"),
        )
        assertEquals(listOf("b", "a"), ids)
    }

    @Test
    fun `selectResultIds takes at most 12`() {
        val ranked = (1..13).map { "m$it" }
        val ids = AiSemanticSearchPolicy.selectResultIds(
            ranked, ranked.toSet(),
        )
        assertEquals(12, ids.size)
        assertTrue("m13" !in ids)
    }
}
