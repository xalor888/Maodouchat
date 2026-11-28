// Fuzz / random-oracle tests for GroupAnnouncementPolicy
// (checklist line 931, 155th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: input is cut
// to the first 1200 characters, with no trim. String.take counts
// UTF-16 units, so an astral character straddling the 1200-unit
// boundary leaves a lone high surrogate behind -- the oracle keeps
// the same unit semantics.
package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun filterInputOracle155(raw: String): String = raw.take(1200)

// ---------- generators ----------

internal val ANNOUNCE_ALPHABET155 = "abcXYZ019 \t\u00a0\u00e9\u4e2d\u0085\u2003\n"

internal fun randomAnnouncementChar155(rnd: Random): String = when (rnd.nextInt(10)) {
    // Astral character (emoji): two UTF-16 units.
    0 -> "\uD83D\uDE00"
    // Lone surrogate: one UTF-16 unit that pairs with nothing.
    1 -> rnd.nextInt(0xD800, 0xE000).toChar().toString()
    else -> ANNOUNCE_ALPHABET155[rnd.nextInt(ANNOUNCE_ALPHABET155.length)].toString()
}

internal fun randomAnnouncement155(rnd: Random): String {
    val lengthUnits = when (rnd.nextInt(10)) {
        0 -> rnd.nextInt(0, 11)
        1 -> rnd.nextInt(1190, 1211)
        2 -> rnd.nextInt(0, 2401)
        else -> rnd.nextInt(0, 1301)
    }
    val sb = StringBuilder()
    var units = 0
    while (units < lengthUnits) {
        val c = randomAnnouncementChar155(rnd)
        sb.append(c)
        units += c.length
    }
    return sb.toString()
}

class GroupAnnouncementPolicyFuzzTest {

    private fun check(raw: String) {
        assertEquals(
            filterInputOracle155(raw),
            GroupAnnouncementPolicy.filterInput(raw),
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Hand-computed expectations; oracle must agree with both.
        val cases = listOf(
            "" to "",
            "a" to "a",
            "a".repeat(1199) to "a".repeat(1199),
            "a".repeat(1200) to "a".repeat(1200),
            "a".repeat(1201) to "a".repeat(1200),
            "a".repeat(2400) to "a".repeat(1200),
            // No trim: surrounding whitespace survives.
            "  hello  " to "  hello  ",
            ("  " + "x".repeat(1198) + "  ") to ("  " + "x".repeat(1198)),
            // Astral char straddling the boundary: take(1200) keeps the
            // lone high surrogate (units 1199..1200 of the pair).
            ("a".repeat(1199) + "\uD83D\uDE00") to ("a".repeat(1199) + "\uD83D"),
            ("a".repeat(1200) + "\uD83D\uDE00") to "a".repeat(1200),
            // Exactly 600 emoji = 1200 units: nothing cut.
            "\uD83D\uDE00".repeat(600) to "\uD83D\uDE00".repeat(600),
            "\uD83D\uDE00".repeat(601) to "\uD83D\uDE00".repeat(600),
            // Lone surrogates pass through unchanged.
            "\uD800" to "\uD800",
            ("\uD800".repeat(1201)) to ("\uD800".repeat(1200)),
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, filterInputOracle155(raw))
            assertEquals(filterInputOracle155(raw), GroupAnnouncementPolicy.filterInput(raw))
            check(raw)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(155003)
        repeat(2000) {
            check(randomAnnouncement155(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Length grid x content kind, incl. the 1200-unit boundary.
        val lengths = listOf(0, 1, 2, 1199, 1200, 1201, 1202, 2400)
        for (length in lengths) {
            check("a".repeat(length))
            check("\uD83D\uDE00".repeat(length / 2) + "a".repeat(length % 2))
            check("\uD800".repeat(length))
            check(" \t".repeat(length / 2) + " ".repeat(length % 2))
        }
        // Boundary straddle for every split point of one astral char.
        for (prefix in listOf(1198, 1199, 1200, 1201)) {
            check("a".repeat(prefix) + "\uD83D\uDE00" + "b".repeat(10))
        }
    }
}
