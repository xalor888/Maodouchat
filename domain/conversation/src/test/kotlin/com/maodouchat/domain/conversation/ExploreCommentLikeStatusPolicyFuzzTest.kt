// Fuzz / random-oracle tests for ExploreCommentLikeStatusPolicy
// (checklist line 931, 159th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: a non-blank
// server status wins and only the exact string "LIKED" counts as
// liked; a blank status falls back to the local optimistic toggle.
// Status equality is exact (case-sensitive); blankness is the stdlib
// isBlank rule, so the oracle calls the same stdlib functions the
// production does and the two cannot drift.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun likeResolveOracle159(status: String, toggledLikedByMe: Boolean): Boolean =
    if (status.isNotBlank()) status == "LIKED" else toggledLikedByMe

// ---------- generators ----------

internal val LIKE_STATUS_POOL159 = listOf(
    "LIKED", "liked", "Liked", "lIkEd", "", " ", "  ", "DISLIKED", "UNLIKED",
    "LIKE", "LIKED ", " LIKED", "LIKED\t", "LIKED\n", "LIKED\u00a0",
    "LIKED\u0085", "\u00a0", "\u0085", "LIKEDx", "xLIKED", "LIKEDLIKED",
    "L", "LIK", "LIKEE", "LIKED\u2003", "LI\u0130KED", "ß", "ẞ", "İ",
)

internal val LIKE_ALPHABET159 = "abcXYZ019 \t\u00a0\u00e9\u4e2d\u0085\u2003LIKEDlisudn\u0130\u0131I i\u00df\u1e9eK\u212a k\u017fsS\u00b5"

internal fun randomLikeChar159(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\uD83D\uDE00"
    1 -> rnd.nextInt(0xD800, 0xE000).toChar().toString()
    else -> LIKE_ALPHABET159[rnd.nextInt(LIKE_ALPHABET159.length)].toString()
}

internal fun randomLikeString159(rnd: Random): String {
    val lengthUnits = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    var units = 0
    while (units < lengthUnits) {
        val c = randomLikeChar159(rnd)
        sb.append(c)
        units += c.length
    }
    return sb.toString()
}

internal data class LikeCase159(
    val status: String,
    val toggled: Boolean,
)

internal fun randomLikeCase159(rnd: Random): LikeCase159 {
    val status = when (rnd.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> LIKE_STATUS_POOL159[rnd.nextInt(LIKE_STATUS_POOL159.size)]
        3 -> if (rnd.nextInt(2) == 0) "LIKED" else "liked"
        else -> randomLikeString159(rnd)
    }
    return LikeCase159(status, rnd.nextBoolean())
}

internal fun likeJsonEscape159(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (c.code < 0x20) {
                    val v = c.code
                    sb.append("\\u")
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

internal fun likeResolveWireJson159(case: LikeCase159): String =
    "{\"status\":\"" + likeJsonEscape159(case.status) + "\",\"toggled\":" +
        (if (case.toggled) "true" else "false") + "}"

class ExploreCommentLikeStatusPolicyFuzzTest {

    private fun check(status: String, toggled: Boolean) {
        assertEquals(
            likeResolveOracle159(status, toggled),
            ExploreCommentLikeStatusPolicy.resolve(status, toggled),
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Exact "LIKED" wins over the toggle either way.
        assertEquals(true, ExploreCommentLikeStatusPolicy.resolve("LIKED", true))
        assertEquals(true, ExploreCommentLikeStatusPolicy.resolve("LIKED", false))
        assertEquals(true, likeResolveOracle159("LIKED", false))
        // Any other non-blank status is not liked, toggle or not.
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve("liked", true))
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve("Liked", true))
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve("DISLIKED", true))
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve("UNLIKED", false))
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve("LIKE", true))
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve("LIKED ", true))
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve(" LIKED", true))
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve("LIKEDx", true))
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve("xLIKED", true))
        // Blank status falls back to the local toggle.
        assertEquals(true, ExploreCommentLikeStatusPolicy.resolve("", true))
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve("", false))
        assertEquals(true, ExploreCommentLikeStatusPolicy.resolve(" ", true))
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve(" ", false))
        assertEquals(true, ExploreCommentLikeStatusPolicy.resolve("  \t ", true))
        // NBSP is blank by the stdlib rule, so it falls back to the toggle.
        assertEquals(true, ExploreCommentLikeStatusPolicy.resolve("\u00a0", true))
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve("\u00a0", false))
        // NEL is not blank by the stdlib rule, so it is a (non-LIKED) status.
        assertEquals(false, ExploreCommentLikeStatusPolicy.resolve("\u0085", true))
        assertEquals(false, likeResolveOracle159("\u0085", true))
        for (status in LIKE_STATUS_POOL159) {
            check(status, true)
            check(status, false)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(159001)
        repeat(2000) {
            val case = randomLikeCase159(rnd)
            check(case.status, case.toggled)
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool status against both toggle values.
        for (status in LIKE_STATUS_POOL159) {
            check(status, true)
            check(status, false)
        }
        // Every single alphabet char as the whole status, both toggles.
        for (c in LIKE_ALPHABET159) {
            check(c.toString(), true)
            check(c.toString(), false)
        }
        // "LIKED" with every alphabet char as a prefix or a suffix.
        for (c in LIKE_ALPHABET159) {
            check(c + "LIKED", true)
            check(c + "LIKED", false)
            check("LIKED" + c, true)
            check("LIKED" + c, false)
        }
        // Trailing blanks after LIKED, 0..20 long, both toggles.
        for (n in 0..20) {
            check("LIKED" + " ".repeat(n), true)
            check("LIKED" + " ".repeat(n), false)
            check(" ".repeat(n), true)
            check(" ".repeat(n), false)
        }
        // Repeated LIKED, 0..3 copies, both toggles.
        for (n in 0..3) {
            check("LIKED".repeat(n), true)
            check("LIKED".repeat(n), false)
        }
    }
}
