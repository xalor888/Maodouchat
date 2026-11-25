// Fuzz / random-oracle tests for RecentSearchHistoryPolicy
// (checklist line 931, 156th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: the query is
// prepended, any existing entry equal to it ignoring case is dropped,
// and the result is cut to the first 8 entries. Case-insensitive
// equality is the JDK regionMatches rule (upper, else the lower-of-upper
// fold -- probed per code unit: İ equals ı and I, ß equals ẞ, K equals
// the Kelvin sign); the oracle calls the same stdlib equals the
// production does, so the two cannot drift on case mapping.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal fun pushOracle156(existing: List<String>, query: String): List<String> =
    (listOf(query) + existing.filterNot { it.equals(query, ignoreCase = true) }).take(8)

// ---------- generators ----------

internal val RECENT_WORD_POOL156 = listOf(
    "hello", "HELLO", "HeLLo", "world", "a", "A", "", " ", "q1", "q2",
    "Σ", "σ", "ς", "ΟΣ", "ος", "οσ", "İ", "ı", "I", "i", "ß", "ẞ",
    "K", "K", "k", "ſ", "s", "S", "µ", "μ", "Μ", "Ǆ", "ǅ", "ǆ",
    "Ÿ", "ÿ", "é", "É", "ﬀ", "张三", "群名", "\u00a0", "\u0085",
)

internal val RECENT_ALPHABET156 = "abcXYZ019 \t\u00a0\u00e9\u4e2d\u0085\u2003ΣσςİıIißẞK\u212a kſsSµμΜǄǅǆŸÿéÉ\uFB00"

internal fun randomSearchChar156(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\uD83D\uDE00"
    1 -> rnd.nextInt(0xD800, 0xE000).toChar().toString()
    else -> RECENT_ALPHABET156[rnd.nextInt(RECENT_ALPHABET156.length)].toString()
}

internal fun randomSearchString156(rnd: Random): String {
    val lengthUnits = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 4)
        else -> rnd.nextInt(1, 9)
    }
    val sb = StringBuilder()
    var units = 0
    while (units < lengthUnits) {
        val c = randomSearchChar156(rnd)
        sb.append(c)
        units += c.length
    }
    return sb.toString()
}

internal data class RecentCase156(
    val existing: List<String>,
    val query: String,
)

internal fun randomRecentCase156(rnd: Random): RecentCase156 {
    val count = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(7, 11)
        else -> rnd.nextInt(0, 6)
    }
    // Bias the list towards words from the pool so case collisions hit.
    val existing = List(count) {
        if (rnd.nextInt(10) < 6) RECENT_WORD_POOL156[rnd.nextInt(RECENT_WORD_POOL156.size)]
        else randomSearchString156(rnd)
    }
    val query = if (rnd.nextInt(10) < 4 && existing.isNotEmpty()) {
        // Often re-query an existing entry (possibly case-shifted).
        val base = existing[rnd.nextInt(existing.size)]
        when (rnd.nextInt(3)) {
            0 -> base
            1 -> base.uppercase()
            else -> base.lowercase()
        }
    } else {
        randomSearchString156(rnd)
    }
    return RecentCase156(existing, query)
}

internal fun recentJsonEscape156(s: String): String {
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

internal fun recentCaseWireJson156(case: RecentCase156): String {
    val sb = StringBuilder()
    sb.append("{\"ex\":[")
    case.existing.forEachIndexed { i, e ->
        if (i > 0) sb.append(",")
        sb.append("\"").append(recentJsonEscape156(e)).append("\"")
    }
    sb.append("],\"q\":\"").append(recentJsonEscape156(case.query)).append("\"}")
    return sb.toString()
}

class RecentSearchHistoryPolicyFuzzTest {

    private fun check(existing: List<String>, query: String) {
        assertEquals(
            pushOracle156(existing, query),
            RecentSearchHistoryPolicy.push(existing, query),
        )
    }

    private fun check(case: RecentCase156) = check(case.existing, case.query)

    @Test
    fun pinnedBoundaries() {
        // (existing, query) -> expected
        val cases = listOf(
            (emptyList<String>() to "hello") to listOf("hello"),
            (listOf("Hello", "world") to "hello") to listOf("hello", "world"),
            ((1..8).map { "q$it" } to "new") to listOf("new", "q1", "q2", "q3", "q4", "q5", "q6", "q7"),
            (listOf("a", "b") to "c") to listOf("c", "a", "b"),
            (listOf("x", "hello") to "HELLO") to listOf("HELLO", "x"),
            (listOf("hello") to "HELLO") to listOf("HELLO"),
            // Sigma variants all compare equal ignoring case.
            (listOf("ΟΣ") to "ος") to listOf("ος"),
            (listOf("σ") to "Σ") to listOf("Σ"),
            (listOf("ς") to "Σ") to listOf("Σ"),
            (listOf("σ") to "ς") to listOf("ς"),
            // Sharp-s vs capital sharp-s: lower-of-upper fold equates them.
            (listOf("ß") to "ẞ") to listOf("ẞ"),
            // Kelvin sign vs K: same lower mapping.
            (listOf("K") to "K") to listOf("K"),
            // Dotted capital I equals dotless i and plain I via the fold.
            (listOf("ı") to "İ") to listOf("İ"),
            (listOf("I") to "İ") to listOf("İ"),
            (listOf("i") to "İ") to listOf("İ"),
            // Long s vs S: same upper mapping.
            (listOf("ſ") to "S") to listOf("S"),
            // Micro sign vs Greek mu: same upper mapping.
            (listOf("µ") to "μ") to listOf("μ"),
            // All three case variants of one entry collapse to the query.
            (listOf("a", "A", "a") to "A") to listOf("A"),
            // Dup of the oldest entry: it leaves, the rest shifts, cap still 8.
            ((1..8).map { "q$it" } to "Q8") to listOf("Q8", "q1", "q2", "q3", "q4", "q5", "q6", "q7"),
            // Empty query dedups only the empty entry.
            (listOf("") to "") to listOf(""),
            (listOf(" ") to "") to listOf("", " "),
            // Astral and lone-surrogate entries compare by UTF-16 units.
            (listOf("\uD83D\uDE00") to "\uD83D\uDE00") to listOf("\uD83D\uDE00"),
            (listOf("\uD800") to "\uD800") to listOf("\uD800"),
            // A 9-entry list with the query already at the front stays 8.
            ((1..9).map { "q$it" } to "q1") to listOf("q1", "q2", "q3", "q4", "q5", "q6", "q7", "q8"),
        )
        for ((input, expected) in cases) {
            val (existing, query) = input
            assertEquals(expected, pushOracle156(existing, query))
            check(existing, query)
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(156002)
        repeat(2000) {
            check(randomRecentCase156(rnd))
        }
    }

    @Test
    fun sweepExhaustive() {
        val existingSets = listOf(
            emptyList(),
            listOf("hello"),
            listOf("Hello", "world"),
            listOf("ΟΣ", "ß", "K"),
            listOf("a", "A", "a"),
            listOf("", " "),
            (1..8).map { "q$it" },
            (1..9).map { "q$it" },
        )
        for (query in RECENT_WORD_POOL156) {
            for (existing in existingSets) {
                check(existing, query)
            }
        }
        // Length grid: distinct entries, new query each time.
        for (count in 0..10) {
            check((1..count).map { "q$it" }, "new")
            check((1..count).map { "q$it" }, "Q$count")
        }
    }
}
