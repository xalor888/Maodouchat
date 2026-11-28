package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Independent re-implementation of conversationProfileText: same
// assembly order (stats, time, terms, narrative) but built by hand
// with an explicit loop for the top-terms truncation instead of
// take()/joinToString(), so a slip in either side shows up.
private fun displayTermsOracle192(terms: List<String>): String {
    val sb = StringBuilder()
    var first = true
    for (term in terms.take(8)) {
        if (!first) sb.append(" · ")
        sb.append(term)
        first = false
    }
    return sb.toString()
}

private fun profileTextOracle192(
    profile: ConversationProfile,
    statsText: (messageCount: Int, activeDays: Int) -> String,
    timeDistributionText: (morning: Int, afternoon: Int, evening: Int, night: Int) -> String,
    topTermsText: (terms: String) -> String,
): String {
    val sb = StringBuilder()
    sb.append(statsText(profile.local.messageCount, profile.local.activeDays))
    sb.append('\n')
    sb.append(
        timeDistributionText(
            profile.local.morning,
            profile.local.afternoon,
            profile.local.evening,
            profile.local.night
        )
    )
    if (profile.local.topTerms.isNotEmpty()) {
        sb.append('\n')
        sb.append(topTermsText(displayTermsOracle192(profile.local.topTerms)))
    }
    val narrative = profile.narrative
    if (narrative != null && narrative.trim().isNotEmpty()) {
        sb.append("\n\n")
        sb.append(narrative)
    }
    return sb.toString()
}

// The three formatting lambdas the tests use; the differential drive
// models these exact formats.
private val STATS192: (Int, Int) -> String = { m, a -> "S:$m/$a" }
private val TIME192: (Int, Int, Int, Int) -> String =
    { mo, af, ev, ni -> "T:$mo/$af/$ev/$ni" }
private val TERMS192: (String) -> String = { t -> "K:$t" }

private val TERM_POOL192: List<String> = listOf(
    "", " ", "a", "b", "chat", "hello", "·", "a · b", "x · y · z",
    "😀", "é", "中文", "term\nwith\nnewline", "  padded  ",
    "x".repeat(50), "\t", "a/b", "a,b", "a:b"
)

private val NARRATIVE_POOL192: List<String?> = listOf(
    null, "", " ", "  \n\t ", "narrative", "line1\nline2",
    "  padded  ", "😀 story", "x".repeat(200), "\nleading",
    "trailing\n", "a · b", "tab\there"
)

private const val TERM_ALPHABET192 =
    "abcxyz 019·/,:;_-\n\té😀"

private fun randomTerm192(r: Random): String {
    if (r.nextInt(3) == 0) return TERM_POOL192.random(r)
    val n = r.nextInt(0, 12)
    return buildString { repeat(n) { append(TERM_ALPHABET192.random(r)) } }
}

private fun randomNarrative192(r: Random): String? {
    if (r.nextInt(3) == 0) return NARRATIVE_POOL192.random(r)
    val n = r.nextInt(0, 40)
    return buildString { repeat(n) { append(TERM_ALPHABET192.random(r)) } }
}

private fun randomProfile192(r: Random): ConversationProfile {
    val termCount = r.nextInt(0, 13)
    return ConversationProfile(
        chatId = "c${r.nextInt(100)}",
        local = LocalStats(
            messageCount = r.nextInt(-10, 100_000),
            activeDays = r.nextInt(-5, 370),
            morning = r.nextInt(-5, 1440),
            afternoon = r.nextInt(-5, 1440),
            evening = r.nextInt(-5, 1440),
            night = r.nextInt(-5, 1440),
            topTerms = List(termCount) { randomTerm192(r) }
        ),
        narrative = randomNarrative192(r)
    )
}

private fun boundaryProfile192(r: Random): ConversationProfile {
    val termCount = when (r.nextInt(5)) {
        0 -> 0
        1 -> 1
        2 -> 7
        3 -> 8
        else -> 9
    }
    val narrative = when (r.nextInt(5)) {
        0 -> null
        1 -> ""
        2 -> "   "
        3 -> "\n\t "
        else -> randomNarrative192(r)
    }
    return ConversationProfile(
        chatId = "c1",
        local = LocalStats(
            messageCount = r.nextInt(0, 3),
            activeDays = r.nextInt(0, 3),
            morning = 0,
            afternoon = 0,
            evening = 0,
            night = 0,
            topTerms = List(termCount) { "t$it" }
        ),
        narrative = narrative
    )
}

// ---------- tests ----------

class ConversationProfileTextFuzzTest {

    @Test
    fun profileTextDifferential192() {
        val r = Random(192003)
        repeat(300) {
            val profile = randomProfile192(r)
            assertEquals(
                profileTextOracle192(profile, STATS192, TIME192, TERMS192),
                conversationProfileText(profile, STATS192, TIME192, TERMS192)
            )
        }
    }

    @Test
    fun profileTextBoundaryDifferential192() {
        val r = Random(192004)
        repeat(300) {
            val profile = boundaryProfile192(r)
            assertEquals(
                profileTextOracle192(profile, STATS192, TIME192, TERMS192),
                conversationProfileText(profile, STATS192, TIME192, TERMS192)
            )
        }
    }

    @Test
    fun profileTextPinned192() {
        val base = ConversationProfile(
            chatId = "c1",
            local = LocalStats(
                messageCount = 120, activeDays = 9,
                morning = 10, afternoon = 20, evening = 30, night = 40
            )
        )
        assertEquals(
            "S:120/9\nT:10/20/30/40",
            conversationProfileText(base, STATS192, TIME192, TERMS192)
        )
        val full = base.copy(
            local = base.local.copy(topTerms = listOf("a", "b")),
            narrative = "narrative"
        )
        assertEquals(
            "S:120/9\nT:10/20/30/40\nK:a · b\n\nnarrative",
            conversationProfileText(full, STATS192, TIME192, TERMS192)
        )
        // Nine terms: only the first eight are displayed.
        val nine = base.copy(
            local = base.local.copy(
                topTerms = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
            )
        )
        assertEquals(
            "S:120/9\nT:10/20/30/40\nK:1 · 2 · 3 · 4 · 5 · 6 · 7 · 8",
            conversationProfileText(nine, STATS192, TIME192, TERMS192)
        )
        // A term containing the separator is passed through untouched.
        val sep = base.copy(local = base.local.copy(topTerms = listOf("a · b", "c")))
        assertEquals(
            "S:120/9\nT:10/20/30/40\nK:a · b · c",
            conversationProfileText(sep, STATS192, TIME192, TERMS192)
        )
        // Blank narratives (empty, spaces, newline/tab only) add nothing.
        for (blank in listOf("", "   ", "\n\t ")) {
            val p = base.copy(
                local = base.local.copy(topTerms = listOf("a")),
                narrative = blank
            )
            assertEquals(
                "S:120/9\nT:10/20/30/40\nK:a",
                conversationProfileText(p, STATS192, TIME192, TERMS192)
            )
        }
        // A narrative is appended verbatim, newlines included.
        val multi = base.copy(narrative = "line1\nline2")
        assertEquals(
            "S:120/9\nT:10/20/30/40\n\nline1\nline2",
            conversationProfileText(multi, STATS192, TIME192, TERMS192)
        )
        // An empty term still counts as a terms line.
        val emptyTerm = base.copy(local = base.local.copy(topTerms = listOf("")))
        assertEquals(
            "S:120/9\nT:10/20/30/40\nK:",
            conversationProfileText(emptyTerm, STATS192, TIME192, TERMS192)
        )
    }
}
