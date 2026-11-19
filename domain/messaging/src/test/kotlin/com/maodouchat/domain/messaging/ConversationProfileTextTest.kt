package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationProfileTextTest {

    private val stats: (Int, Int) -> String = { messageCount, activeDays ->
        "stats:$messageCount/$activeDays"
    }
    private val timeDistribution: (Int, Int, Int, Int) -> String =
        { morning, afternoon, evening, night ->
            "time:$morning/$afternoon/$evening/$night"
        }
    private val topTerms: (String) -> String = { terms -> "terms:$terms" }

    private fun profile(
        topTerms: List<String> = emptyList(),
        narrative: String? = null,
    ) = ConversationProfile(
        chatId = "c1",
        local = LocalStats(
            messageCount = 120,
            activeDays = 9,
            morning = 10,
            afternoon = 20,
            evening = 30,
            night = 40,
            topTerms = topTerms
        ),
        narrative = narrative
    )

    @Test
    fun fullProfileKeepsOrderAndSeparators() {
        val text = conversationProfileText(
            profile(listOf("a", "b"), "narrative"),
            stats, timeDistribution, topTerms
        )
        assertEquals(
            "stats:120/9\ntime:10/20/30/40\nterms:a · b\n\nnarrative",
            text
        )
    }

    @Test
    fun noTopTermsOmitsTermsLine() {
        val text = conversationProfileText(profile(), stats, timeDistribution, topTerms)
        assertEquals("stats:120/9\ntime:10/20/30/40", text)
    }

    @Test
    fun blankNarrativeIsOmitted() {
        val text = conversationProfileText(
            profile(listOf("a"), "   "),
            stats, timeDistribution, topTerms
        )
        assertEquals("stats:120/9\ntime:10/20/30/40\nterms:a", text)
    }

    @Test
    fun topTermsTruncatedToEight() {
        val terms = (1..10).map { "t$it" }
        val text = conversationProfileText(
            profile(terms),
            stats, timeDistribution, topTerms
        )
        assertEquals(
            "stats:120/9\ntime:10/20/30/40\nterms:t1 · t2 · t3 · t4 · t5 · t6 · t7 · t8",
            text
        )
    }
}
