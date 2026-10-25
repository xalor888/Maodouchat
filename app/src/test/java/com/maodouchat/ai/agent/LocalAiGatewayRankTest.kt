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
}
