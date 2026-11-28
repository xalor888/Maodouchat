package com.maodouchat.domain.contacts

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fuzz / random-oracle tests for [NearbyRemainingPolicy] (checklist line 931,
 * 58th block). Test-only, zero production changes.
 *
 * The oracle re-implements the three-way split by hand (Disabled / Soon /
 * ceil minutes with a 64-bit wrap and a 32-bit truncation). Random inputs
 * use Random(42), fresh per @Test.
 */
class NearbyRemainingPolicyFuzzTest {

// ---------- C2 NearbyRemainingPolicy oracle ----------

private sealed interface OracleHint {
    object Disabled : OracleHint
    object Soon : OracleHint
    data class Minutes(val minutes: Int) : OracleHint
}

private fun hintOracle(isSharing: Boolean, remainingMs: Long): OracleHint = when {
    !isSharing -> OracleHint.Disabled
    remainingMs < 60_000L -> OracleHint.Soon
    else -> OracleHint.Minutes((((remainingMs + 59_999L) / 60_000L).toInt()).coerceAtLeast(1))
}

private fun encOracleHint(h: OracleHint): String = when (h) {
    OracleHint.Disabled -> "D"
    OracleHint.Soon -> "S"
    is OracleHint.Minutes -> "M:" + h.minutes
}

// Boundary-biased remaining times: the 60s gate, the ceil, the minute
// count overflowing Int, and the Long add overflowing near MAX_VALUE.
private fun randomMs(rnd: Random): Long = when (rnd.nextInt(10)) {
    0 -> 59_999L
    1 -> 60_000L
    2 -> 60_001L
    3 -> 120_000L
    4 -> Long.MAX_VALUE
    5 -> Long.MIN_VALUE
    6 -> 128_849_018_820_001L
    7 -> 128_849_018_820_000L
    else -> randomLong(rnd)
}

// ---------- shared generators (Random(42) in the CI tests) ----------

// The probed Kotlin whitespace set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as whitespace, U+0085 NEL does not.
private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Digits Char.isDigit keeps (Nd), plus U+00B2 which is NOT a digit in
// Kotlin (unlike Python str.isdigit) — a deliberate discriminator.
private val DIGIT_CHARS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '\u0660', '\u06F0', '\u0966', '\uFF10', '\u00B2'
)

private val PLAIN_CHARS = charArrayOf(
    'a', 'Z', '0', '_', '-', '.', '@', 'é', '中', '\u200B', '\uFEFF',
    '\u0085', '\uD800', '|', ':', 'C', 'A', 'P', 'T', 'U', 'R', 'E', '/',
    '?', '%', ' '
)

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0, 1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2, 3 -> { sb.append(DIGIT_CHARS[rnd.nextInt(DIGIT_CHARS.size)]); n++ }
            4 -> {
                // Astral char (emoji or astral digit U+104A0): 2 code units.
                // Its surrogates are not digits and not whitespace.
                val cp = if (rnd.nextBoolean()) 0x1F600 else 0x104A0
                sb.append(Character.toChars(cp)); n += 2
            }
            else -> { sb.append(PLAIN_CHARS[rnd.nextInt(PLAIN_CHARS.size)]); n++ }
        }
    }
    return sb.toString()
}

private fun randomLong(rnd: Random): Long = when (rnd.nextInt(8)) {
    0 -> 0L; 1 -> 1L; 2 -> -1L; 3 -> 350L; 4 -> -350L
    5 -> Long.MIN_VALUE; 6 -> Long.MAX_VALUE
    else -> rnd.nextLong()
}

    private fun NearbyRemainingHint.toOracle(): OracleHint = when (this) {
        NearbyRemainingHint.Disabled -> OracleHint.Disabled
        NearbyRemainingHint.Soon -> OracleHint.Soon
        is NearbyRemainingHint.Minutes -> OracleHint.Minutes(minutes)
    }

    @Test
    fun hintRandom() {
        val rnd = Random(42)
        repeat(300) {
            val isSharing = rnd.nextBoolean()
            val remainingMs = randomMs(rnd)
            assertEquals(
                hintOracle(isSharing, remainingMs),
                NearbyRemainingPolicy.nearbyRemainingHint(isSharing, remainingMs).toOracle()
            )
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // Not sharing: Disabled whatever the clock says.
        assertEquals(
            OracleHint.Disabled,
            NearbyRemainingPolicy.nearbyRemainingHint(false, 120_000L).toOracle()
        )
        assertEquals(
            OracleHint.Disabled,
            NearbyRemainingPolicy.nearbyRemainingHint(false, Long.MAX_VALUE).toOracle()
        )
        assertEquals(
            OracleHint.Disabled,
            NearbyRemainingPolicy.nearbyRemainingHint(false, 0L).toOracle()
        )
        // Under one minute (including zero and negatives): Soon.
        assertEquals(
            OracleHint.Soon,
            NearbyRemainingPolicy.nearbyRemainingHint(true, 0L).toOracle()
        )
        assertEquals(
            OracleHint.Soon,
            NearbyRemainingPolicy.nearbyRemainingHint(true, -1L).toOracle()
        )
        assertEquals(
            OracleHint.Soon,
            NearbyRemainingPolicy.nearbyRemainingHint(true, 59_999L).toOracle()
        )
        assertEquals(
            OracleHint.Soon,
            NearbyRemainingPolicy.nearbyRemainingHint(true, Long.MIN_VALUE).toOracle()
        )
        // Partial minutes round up.
        assertEquals(
            OracleHint.Minutes(1),
            NearbyRemainingPolicy.nearbyRemainingHint(true, 60_000L).toOracle()
        )
        assertEquals(
            OracleHint.Minutes(2),
            NearbyRemainingPolicy.nearbyRemainingHint(true, 60_001L).toOracle()
        )
        assertEquals(
            OracleHint.Minutes(2),
            NearbyRemainingPolicy.nearbyRemainingHint(true, 119_999L).toOracle()
        )
        assertEquals(
            OracleHint.Minutes(2),
            NearbyRemainingPolicy.nearbyRemainingHint(true, 120_000L).toOracle()
        )
        assertEquals(
            OracleHint.Minutes(3),
            NearbyRemainingPolicy.nearbyRemainingHint(true, 120_001L).toOracle()
        )
        // Minutes just fit in Int, then the toInt() truncation wraps and
        // coerceAtLeast(1) catches it.
        assertEquals(
            OracleHint.Minutes(Int.MAX_VALUE),
            NearbyRemainingPolicy.nearbyRemainingHint(true, 128_849_018_820_000L).toOracle()
        )
        assertEquals(
            OracleHint.Minutes(1),
            NearbyRemainingPolicy.nearbyRemainingHint(true, 128_849_018_820_001L).toOracle()
        )
        // Near Long.MAX_VALUE the +59_999 addition wraps negative, so the
        // quotient is negative and coerceAtLeast(1) catches it; one step
        // below the wrap the quotient itself truncates to a positive int.
        assertEquals(
            OracleHint.Minutes(1),
            NearbyRemainingPolicy.nearbyRemainingHint(true, Long.MAX_VALUE).toOracle()
        )
        assertEquals(
            OracleHint.Minutes(1_692_789_776),
            NearbyRemainingPolicy.nearbyRemainingHint(true, Long.MAX_VALUE - 59_999L).toOracle()
        )
    }
}
