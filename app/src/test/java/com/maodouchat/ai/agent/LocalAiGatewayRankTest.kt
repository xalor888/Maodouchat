package com.maodouchat.ai.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAiGatewayRankTest {

    @Test
    fun `rankSemantic scores local tokens without a network call`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "meeting notes",
            listOf(
                "m1" to "random photo",
                "m2" to "the meeting notes are here",
                "m3" to "meeting later"
            )
        )
        assertEquals(listOf("m2", "m3"), ranked)
    }

    @Test
    fun `blank query returns empty list`() = runBlocking {
        assertEquals(emptyList<String>(), LocalAiGateway.rankSemantic("   ", listOf("m1" to "meeting notes")))
    }

    @Test
    fun `candidates without any token hit are filtered out`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "quarterly report",
            listOf("m1" to "random photo", "m2" to "the quarterly report draft")
        )
        assertEquals(listOf("m2"), ranked)
    }

    @Test
    fun `more token hits rank before fewer hits`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "alpha beta",
            listOf("c1" to "alpha only here", "c2" to "alpha and beta both here")
        )
        assertEquals(listOf("c2", "c1"), ranked)
    }

    @Test
    fun `matching ignores case`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "Meeting",
            listOf("m1" to "MEETING notes are here")
        )
        assertEquals(listOf("m1"), ranked)
    }

    @Test
    fun `scored ranking returns descending scores`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "red blue",
            listOf("c1" to "red car", "c2" to "red and blue bike")
        )
        assertEquals(listOf("c2" to 2.0, "c1" to 1.0), scored)
    }

    @Test
    fun `query tokens beyond twelve are ignored`() = runBlocking {
        val query = "apple banana cherry dragon eagle falcon gorilla hotel india juliet kilo lima mike"
        val ranked = LocalAiGateway.rankSemantic(
            query,
            listOf(
                "c1" to "apple banana cherry dragon eagle falcon gorilla hotel india juliet kilo lima",
                "c2" to "mike only"
            )
        )
        assertEquals(listOf("c1"), ranked)
        val scored = LocalAiGateway.rankSemanticScored(query, listOf("c1" to "apple banana cherry dragon eagle falcon gorilla hotel india juliet kilo lima"))
        assertEquals(listOf("c1" to 12.0), scored)
    }

    @Test
    fun `repeated query tokens count once per occurrence`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "red red blue",
            listOf("c1" to "red car", "c2" to "blue bike")
        )
        assertEquals(listOf("c1" to 2.0, "c2" to 1.0), scored)
    }

    @Test
    fun `equal scores keep candidate order`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "red",
            listOf("c1" to "red one", "c2" to "red two")
        )
        assertEquals(listOf("c1", "c2"), ranked)
        val scored = LocalAiGateway.rankSemanticScored(
            "red",
            listOf("c1" to "red one", "c2" to "red two")
        )
        assertEquals(listOf("c1" to 1.0, "c2" to 1.0), scored)
    }

    @Test
    fun `substring token matches count without word boundaries`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "meet",
            listOf("m1" to "random photo", "m2" to "meeting notes")
        )
        assertEquals(listOf("m2"), ranked)
    }

    @Test
    fun `tabs and newlines split query tokens like spaces`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "meeting\tnotes",
            listOf("m1" to "random photo", "m2" to "the meeting notes are here")
        )
        assertEquals(listOf("m2"), ranked)
    }

    @Test
    fun `empty candidates rank to empty`() = runBlocking {
        assertEquals(emptyList<String>(), LocalAiGateway.rankSemantic("red", emptyList()))
        assertEquals(emptyList<Pair<String, Double>>(), LocalAiGateway.rankSemanticScored("red", emptyList()))
    }

    @Test
    fun `empty string query ranks to empty`() = runBlocking {
        val candidates = listOf("m1" to "red car")
        assertEquals(emptyList<String>(), LocalAiGateway.rankSemantic("", candidates))
        assertEquals(emptyList<Pair<String, Double>>(), LocalAiGateway.rankSemanticScored("", candidates))
    }

    @Test
    fun `zero score candidates are filtered between equal hits`() = runBlocking {
        val candidates = listOf("c1" to "red one", "c2" to "nothing here", "c3" to "red two")
        assertEquals(listOf("c1", "c3"), LocalAiGateway.rankSemantic("red", candidates))
        assertEquals(
            listOf("c1" to 1.0, "c3" to 1.0),
            LocalAiGateway.rankSemanticScored("red", candidates)
        )
    }

    @Test
    fun `twelfth token counts and earlier tokens score`() = runBlocking {
        // 子串匹配无词边界：用互不为子串的词，避免 w1 命中 w12 这类前缀干扰。
        val query = "apple banana cherry dragon eagle falcon gorilla hotel india juliet kilo lima"
        val scored = LocalAiGateway.rankSemanticScored(
            query,
            listOf("c1" to "lima only", "c2" to "apple banana cherry")
        )
        assertEquals(listOf("c2" to 3.0, "c1" to 1.0), scored)
    }

    @Test
    fun `duplicate candidate ids are both kept`() = runBlocking {
        val candidates = listOf("c1" to "red one", "c1" to "red two")
        assertEquals(listOf("c1", "c1"), LocalAiGateway.rankSemantic("red", candidates))
        assertEquals(
            listOf("c1" to 1.0, "c1" to 1.0),
            LocalAiGateway.rankSemanticScored("red", candidates)
        )
    }

    @Test
    fun `surrounding whitespace in query does not change ranking`() = runBlocking {
        val ranked = LocalAiGateway.rankSemantic(
            "  alpha beta  ",
            listOf("c1" to "alpha only here", "c2" to "alpha and beta both here")
        )
        assertEquals(listOf("c2", "c1"), ranked)
    }

    @Test
    fun `token repeated in candidate text counts once`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "red",
            listOf("c1" to "red red red car")
        )
        assertEquals(listOf("c1" to 1.0), scored)
    }

    @Test
    fun `uppercase query and candidate text score the same`() = runBlocking {
        assertEquals(
            listOf("c1" to 1.0),
            LocalAiGateway.rankSemanticScored("BLUE", listOf("c1" to "BLUE SKY"))
        )
        assertEquals(
            listOf("c1" to 1.0),
            LocalAiGateway.rankSemanticScored("blue", listOf("c1" to "blue sky"))
        )
    }

    @Test
    fun `scored ranking preserves input order across three equal hits`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "red",
            listOf("c1" to "red one", "c2" to "red two", "c3" to "red three")
        )
        assertEquals(listOf("c1" to 1.0, "c2" to 1.0, "c3" to 1.0), scored)
    }

    @Test
    fun `missing token contributes zero without penalty`() = runBlocking {
        val scored = LocalAiGateway.rankSemanticScored(
            "red blue green",
            listOf("c1" to "red blue")
        )
        assertEquals(listOf("c1" to 2.0), scored)
    }
}
