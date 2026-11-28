package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// preview restated by hand: keep the first three suggestions, in
// order; shorter lists come back unchanged.
internal fun previewOracle171(suggestions: List<ArchiveSuggestion>): List<ArchiveSuggestion> =
    suggestions.take(3)

// Production preview returns the first three suggestions.
internal fun previewProduction171(suggestions: List<ArchiveSuggestion>): List<ArchiveSuggestion> =
    ArchiveSuggestionDisplayPolicy.preview(suggestions)

// ---------- generators ----------

internal fun suggestion171(chatId: String, score: Int, reason: String): ArchiveSuggestion =
    ArchiveSuggestion(chatId = chatId, score = score, reason = reason)

internal data class PreviewCase171(val items: List<ArchiveSuggestion>)

internal val PREVIEW_CHAT_ID_POOL171: List<String> = listOf(
    "", "c1", "c2", " ", "c1 ", "C1"
)

internal val PREVIEW_SCORE_POOL171: List<Int> = listOf(
    0, 1, -1, 42, Int.MAX_VALUE, Int.MIN_VALUE
)

internal val PREVIEW_REASON_POOL171: List<String> = listOf(
    "", "r1", "x".repeat(20), "a b", "  "
)

internal fun randomSuggestion171(rnd: Random): ArchiveSuggestion =
    suggestion171(
        PREVIEW_CHAT_ID_POOL171[rnd.nextInt(PREVIEW_CHAT_ID_POOL171.size)],
        PREVIEW_SCORE_POOL171[rnd.nextInt(PREVIEW_SCORE_POOL171.size)],
        PREVIEW_REASON_POOL171[rnd.nextInt(PREVIEW_REASON_POOL171.size)]
    )

internal fun randomPreviewCase171(rnd: Random): PreviewCase171 {
    val n = rnd.nextInt(6)
    return PreviewCase171(List(n) { randomSuggestion171(rnd) })
}

internal fun previewWireJson171(c: PreviewCase171): String {
    // escJson171 lives in ExplorePostReplacePolicyFuzzTest (same package).
    val items = c.items.joinToString(",") {
        "{\"chatId\":${escJson171(it.chatId)},\"score\":${it.score}," +
            "\"reason\":${escJson171(it.reason)}}"
    }
    return "{\"items\":[$items]}"
}

class ArchiveSuggestionDisplayPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // The limit itself is a fixed constant.
        assertEquals(3, ArchiveSuggestionDisplayPolicy.PREVIEW_LIMIT)
        val s1 = suggestion171("c1", 10, "r1")
        val s2 = suggestion171("c2", 20, "r2")
        val s3 = suggestion171("c3", 30, "r3")
        val s4 = suggestion171("c4", 40, "r4")
        val s5 = suggestion171("c5", 50, "r5")
        val cases = listOf(
            emptyList<ArchiveSuggestion>() to emptyList(),
            listOf(s1) to listOf(s1),
            listOf(s1, s2) to listOf(s1, s2),
            listOf(s1, s2, s3) to listOf(s1, s2, s3),
            listOf(s1, s2, s3, s4) to listOf(s1, s2, s3),
            listOf(s1, s2, s3, s4, s5) to listOf(s1, s2, s3),
            // The cut is positional: scores and reasons do not matter.
            listOf(
                suggestion171("c9", Int.MIN_VALUE, ""),
                suggestion171("c8", Int.MAX_VALUE, "x".repeat(20)),
                suggestion171("c7", -1, "  "),
                suggestion171("c6", 0, "r")
            ) to listOf(
                suggestion171("c9", Int.MIN_VALUE, ""),
                suggestion171("c8", Int.MAX_VALUE, "x".repeat(20)),
                suggestion171("c7", -1, "  ")
            )
        )
        for ((input, expected) in cases) {
            assertEquals(expected, previewProduction171(input), "preview(len=${input.size})")
            assertEquals(expected, previewOracle171(input), "oracle(len=${input.size})")
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(171004)
        repeat(4000) {
            val c = randomPreviewCase171(rnd)
            assertEquals(
                previewOracle171(c.items),
                previewProduction171(c.items),
                "preview(random)"
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every list length from 0 to 6 with fixed ids.
        for (n in 0..6) {
            val items = List(n) { i -> suggestion171("c$i", i, "r$i") }
            assertEquals(
                previewOracle171(items),
                previewProduction171(items),
                "sweep-len(n=$n)"
            )
        }
        // Every score in the pool at each of the first four slots.
        for (score in PREVIEW_SCORE_POOL171) {
            val items = listOf(
                suggestion171("c1", score, "r1"),
                suggestion171("c2", score, "r2"),
                suggestion171("c3", score, "r3"),
                suggestion171("c4", score, "r4")
            )
            assertEquals(
                previewOracle171(items),
                previewProduction171(items),
                "sweep-score($score)"
            )
        }
        // Every chat id in the pool as the single item, and as the
        // fourth item (just past the cut).
        for (chatId in PREVIEW_CHAT_ID_POOL171) {
            assertEquals(
                previewOracle171(listOf(suggestion171(chatId, 1, "r"))),
                previewProduction171(listOf(suggestion171(chatId, 1, "r"))),
                "sweep-chatId-single($chatId)"
            )
            val items = listOf(
                suggestion171("c1", 1, "r1"),
                suggestion171("c2", 2, "r2"),
                suggestion171("c3", 3, "r3"),
                suggestion171(chatId, 4, "r4")
            )
            assertEquals(
                previewOracle171(items),
                previewProduction171(items),
                "sweep-chatId-fourth($chatId)"
            )
        }
    }
}
