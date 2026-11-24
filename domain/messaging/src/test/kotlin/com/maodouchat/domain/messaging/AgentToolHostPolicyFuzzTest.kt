// Fuzz / random-oracle tests for AgentToolHostPolicy
// (checklist line 931, 127th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: an error
// string, a 160-unit preview, and a 6,000-unit truncation
// (AgentToolPolicy.MAX_TOOL_RESULT_CHARS). fuzz-70 only pinned
// unknownTool with four literals as a delegate check; previewFallback
// and truncateResult were never fuzzed and no AgentToolHostPolicy
// fuzz test existed.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- shared oracle ----------

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun takeOracle(s: String, n: Int): String = if (s.length <= n) s else s.substring(0, n)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit(rnd, sb) }
    return sb.toString()
}

private fun athUnknownOracle(name: String): String = "Error: unknown tool $name"

private fun athPreviewOracle(name: String, argumentsJson: String): String =
    "$name ${takeOracle(argumentsJson, 160)}"

private fun athTruncOracle(text: String): String = takeOracle(text, 6000)

private val ATH_NAME_POOL = arrayOf(
    "", " ", "rewrite_text", "no_such_tool", "a b", "x", "tool|name",
    "a\uD800b", "\uDC00", "search", "send_message", "a\nb"
)

private val ATH_ARGS_POOL = arrayOf(
    "", "{}", "{\"q\":\"x\"}", "a".repeat(159), "a".repeat(160),
    "a".repeat(161), "x|y^z", "b".repeat(300), "\u00A0".repeat(200)
)

private fun genAthName(rnd: Random): String =
    if (rnd.nextInt(3) == 0) ATH_NAME_POOL[rnd.nextInt(ATH_NAME_POOL.size)] else randomText(rnd, 24)

private fun genAthArgs(rnd: Random): String {
    return when (rnd.nextInt(4)) {
        0 -> ATH_ARGS_POOL[rnd.nextInt(ATH_ARGS_POOL.size)]
        1 -> "a".repeat(rnd.nextInt(0, 400))
        else -> randomText(rnd, 200)
    }
}

private fun genAthText(rnd: Random): String {
    return when (rnd.nextInt(4)) {
        0 -> "a".repeat(rnd.nextInt(0, 6500))
        1 -> ATH_ARGS_POOL[rnd.nextInt(ATH_ARGS_POOL.size)]
        else -> randomText(rnd, 200)
    }
}

class AgentToolHostPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // unknownTool: the four literals fuzz-70 pinned as a delegate
        // check, plus the tool name itself is pasted verbatim.
        assertEquals("Error: unknown tool no_such_tool", AgentToolHostPolicy.unknownTool("no_such_tool"))
        assertEquals("Error: unknown tool ", AgentToolHostPolicy.unknownTool(""))
        assertEquals("Error: unknown tool a b", AgentToolHostPolicy.unknownTool("a b"))
        assertEquals("Error: unknown tool x", AgentToolHostPolicy.unknownTool("x"))
        assertEquals("Error: unknown tool a\uD800b", AgentToolHostPolicy.unknownTool("a\uD800b"))
        assertEquals("Error: unknown tool  ", AgentToolHostPolicy.unknownTool(" "))
        // previewFallback: name + one space + the first 160 UTF-16
        // units of the arguments; the name itself is never truncated.
        assertEquals("tool ", AgentToolHostPolicy.previewFallback("tool", ""))
        assertEquals("tool " + "a".repeat(159), AgentToolHostPolicy.previewFallback("tool", "a".repeat(159)))
        assertEquals("tool " + "a".repeat(160), AgentToolHostPolicy.previewFallback("tool", "a".repeat(160)))
        assertEquals("tool " + "a".repeat(160), AgentToolHostPolicy.previewFallback("tool", "a".repeat(161)))
        assertEquals("tool " + "a".repeat(160), AgentToolHostPolicy.previewFallback("tool", "a".repeat(300)))
        assertEquals("a very long tool name " + "b".repeat(160),
            AgentToolHostPolicy.previewFallback("a very long tool name", "b".repeat(500)))
        // A lone surrogate at the 160-unit cut survives verbatim
        // (take counts UTF-16 units, it does not split code points
        // cleanly and never drops the dangling half).
        assertEquals("t " + "x".repeat(159) + "\uD800",
            AgentToolHostPolicy.previewFallback("t", "x".repeat(159) + "\uD800" + "tail"))
        assertEquals("t " + "x".repeat(160),
            AgentToolHostPolicy.previewFallback("t", "x".repeat(160) + "\uD800"))
        // truncateResult: first 6,000 UTF-16 units; short text passes
        // through untouched.
        assertEquals("", AgentToolHostPolicy.truncateResult(""))
        assertEquals("hello", AgentToolHostPolicy.truncateResult("hello"))
        assertEquals("a".repeat(5999), AgentToolHostPolicy.truncateResult("a".repeat(5999)))
        assertEquals("a".repeat(6000), AgentToolHostPolicy.truncateResult("a".repeat(6000)))
        assertEquals("a".repeat(6000), AgentToolHostPolicy.truncateResult("a".repeat(6001)))
        assertEquals("a".repeat(6000), AgentToolHostPolicy.truncateResult("a".repeat(12000)))
        // 6,000 units = 3,000 astral chars: the cut lands exactly on
        // a code-point boundary here; a lone high surrogate one unit
        // past the boundary is kept as-is (take never drops it).
        assertEquals("\uD83D\uDE00".repeat(3000), AgentToolHostPolicy.truncateResult("\uD83D\uDE00".repeat(3000) + "tail"))
        assertEquals("\uD83D\uDE00".repeat(3000), AgentToolHostPolicy.truncateResult("\uD83D\uDE00".repeat(2999) + "\uD83D\uDE00" + "tail"))
        assertEquals("x".repeat(5999) + "\uD800",
            AgentToolHostPolicy.truncateResult("x".repeat(5999) + "\uD800" + "tail"))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle127.genAth consumption order exactly, so seed
        // 127003 replays the same 1500 GEN lines drive127.py ran.
        val rnd = Random(127003)
        repeat(1500) {
            when (rnd.nextInt(3)) {
                0 -> {
                    val name = genAthName(rnd)
                    assertEquals(athUnknownOracle(name), AgentToolHostPolicy.unknownTool(name))
                }
                1 -> {
                    val name = genAthName(rnd)
                    val args = genAthArgs(rnd)
                    assertEquals(athPreviewOracle(name, args), AgentToolHostPolicy.previewFallback(name, args))
                }
                else -> {
                    val text = genAthText(rnd)
                    assertEquals(athTruncOracle(text), AgentToolHostPolicy.truncateResult(text))
                }
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every name in the pool, the full preview cross product, and
        // length sweeps around both cuts -- the same inputs drive127.py
        // sweeps.
        for (name in ATH_NAME_POOL) {
            assertEquals(athUnknownOracle(name), AgentToolHostPolicy.unknownTool(name))
            for (args in ATH_ARGS_POOL) {
                assertEquals(athPreviewOracle(name, args), AgentToolHostPolicy.previewFallback(name, args))
            }
        }
        for (n in listOf(155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 0, 1, 2, 200, 1000)) {
            assertEquals(athPreviewOracle("tool", "a".repeat(n)), AgentToolHostPolicy.previewFallback("tool", "a".repeat(n)))
        }
        for (n in listOf(0, 1, 2, 5995, 5996, 5997, 5998, 5999, 6000, 6001, 6002, 6005, 6010, 6500, 12000)) {
            assertEquals(athTruncOracle("a".repeat(n)), AgentToolHostPolicy.truncateResult("a".repeat(n)))
            assertEquals(athTruncOracle("ab".repeat(n / 2 + 1)), AgentToolHostPolicy.truncateResult("ab".repeat(n / 2 + 1)))
        }
        assertEquals(athTruncOracle("\uD83D\uDE00".repeat(3000) + "tail"),
            AgentToolHostPolicy.truncateResult("\uD83D\uDE00".repeat(3000) + "tail"))
        assertEquals(athTruncOracle("\uD83D\uDE00".repeat(2999) + "\uD800" + "tail"),
            AgentToolHostPolicy.truncateResult("\uD83D\uDE00".repeat(2999) + "\uD800" + "tail"))
        assertEquals(athTruncOracle("x".repeat(5999) + "\uD800" + "tail"),
            AgentToolHostPolicy.truncateResult("x".repeat(5999) + "\uD800" + "tail"))
        assertEquals(athTruncOracle("x".repeat(6000) + "\uD800"),
            AgentToolHostPolicy.truncateResult("x".repeat(6000) + "\uD800"))
    }
}
