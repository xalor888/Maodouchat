package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [ExploreSearchPolicy] (checklist line 931, 43rd block).
 * Test-only, zero production changes.
 *
 * The oracle reimplements case-insensitive substring search from scratch, the same
 * code-point comparison JDK 17's String.regionMatches uses (see the 42nd block's
 * NotificationCenterFilterPolicyFuzzTest). A Python mini-model differentially checks
 * this oracle against the real JDK on 7348 primitive ops plus 5000 random inputs
 * per cluster (0 diffs) before this file was written.
 *
 * Two asymmetries the oracle must reproduce: matches() does not trim the query
 * (filter() does), and trim()/isBlank() use Kotlin Char.isWhitespace, so NBSP is
 * blank and trimmed while NEL is not.
 * Random strings are built one code point at a time, so they stay well-formed.
 */
class ExploreSearchPolicyFuzzTest {

    private fun compareCodePointIgnoreCase(c1: Int, c2: Int): Boolean {
        if (c1 == c2) return true
        val u1 = Character.toUpperCase(c1)
        val u2 = Character.toUpperCase(c2)
        if (u1 == u2) return true
        return Character.toLowerCase(u1) == Character.toLowerCase(u2)
    }

    // Code-point-aware contains(ignoreCase = true); never indexes a UTF-16 unit.
    private fun containsIgnoreCase(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return true
        val needleCps = needle.codePointCount(0, needle.length)
        val hayCps = haystack.codePointCount(0, haystack.length)
        if (needleCps > hayCps) return false
        for (startCp in 0..hayCps - needleCps) {
            var hIdx = haystack.offsetByCodePoints(0, startCp)
            var nIdx = 0
            var ok = true
            while (nIdx < needle.length) {
                val hc = haystack.codePointAt(hIdx)
                val nc = needle.codePointAt(nIdx)
                if (!compareCodePointIgnoreCase(hc, nc)) {
                    ok = false
                    break
                }
                hIdx += Character.charCount(hc)
                nIdx += Character.charCount(nc)
            }
            if (ok) return true
        }
        return false
    }

    private fun isKotlinWhitespace(c: Char): Boolean =
        c in '\u0009'..'\u000D' || c == ' ' || c in '\u001C'..'\u001F' ||
            c == '\u1680' || c in '\u2000'..'\u200A' ||
            c == '\u2028' || c == '\u2029' || c == '\u205F' || c == '\u3000' ||
            c == '\u00A0' || c == '\u2007' || c == '\u202F'

    private fun kotlinTrim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isKotlinWhitespace(s[a])) a++
        while (b > a && isKotlinWhitespace(s[b - 1])) b--
        return s.substring(a, b)
    }

    private fun kotlinBlank(s: String): Boolean =
        s.isEmpty() || s.all { isKotlinWhitespace(it) }

    private data class Item(val content: String, val authorName: String, val authorId: String)

    private fun oracleMatches(item: Item, query: String): Boolean =
        containsIgnoreCase(item.content, query) ||
            containsIgnoreCase(item.authorName, query) ||
            containsIgnoreCase(item.authorId, query)

    private fun oracleFilter(items: List<Item>, query: String): List<Item> {
        val trimmed = kotlinTrim(query)
        if (kotlinBlank(trimmed)) return items
        return items.filter { oracleMatches(it, trimmed) }
    }

    private val words = listOf(
        "alice", "Alice", "ALICE", "Bob", "bob", "u1", "x_9", "hello", "world",
        "test", "hello world", "at school", "i", "I", "k", "K", "", " "
    )

    private val edgeCodePoints = listOf(
        "\u0130", "\u0131", "\u00DF", "\u1E9E", "\u03C3", "\u03C2", "\u03A3",
        "\u212A", "\u00C5", "\u00E5", "\uFB00", "\uFB01", "\uFB02", "\u017F",
        "\u01C5", "\u01C6", "\u01C4", "\u01C8", "\u01C9", "\u01C7", "\u01CB",
        "\u01CC", "\u01CA", "\u01F2", "\u01F3", "\u01F1", "\u00B5", "\u039C",
        "\u03BC", "\u10A0", "\u2D00", "\uD801\uDC00", "\uD801\uDC28",
        "\uD801\uDCCF", "\uD801\uDCF7", "\uD83D\uDE00", "\uD83D\uDE01",
        "\u0085", "\u00A0", "\u2007", "\u202F", "\u200B", "\u0307", "\u00AD"
    )

    private fun randomField(rnd: Random): String {
        return when (rnd.nextInt(10)) {
            in 0..4 -> {
                val w = words.random(rnd)
                when (rnd.nextInt(4)) {
                    0 -> w.uppercase()
                    1 -> w.lowercase()
                    else -> w
                }
            }
            in 5..6 -> {
                val alpha = listOf("a", "b", "A", "B", "z", "Z", "0", "1", "9", " ", "_", "-") + edgeCodePoints
                (0 until rnd.nextInt(9)).joinToString("") { alpha.random(rnd) }
            }
            7, 8 -> {
                val w = words.random(rnd)
                val pre = (0 until rnd.nextInt(4)).joinToString("") { listOf("a", "b", " ", "_").random(rnd) }
                val post = (0 until rnd.nextInt(4)).joinToString("") { listOf("a", "b", " ", "_").random(rnd) }
                pre + w + post
            }
            else -> {
                val ctrl = (0..0x20).map { it.toChar().toString() }
                (0 until rnd.nextInt(7)).joinToString("") { (ctrl + listOf(" ", "a", "B")).random(rnd) }
            }
        }
    }

    private fun randomItem(rnd: Random): Item =
        Item(randomField(rnd), randomField(rnd), randomField(rnd))

    // Substring taken at code-point boundaries, so a supplementary pair is never split.
    private fun randomSubstring(rnd: Random, s: String): String {
        val cps = s.codePointCount(0, s.length)
        if (cps == 0) return ""
        val startCp = rnd.nextInt(cps)
        val endCp = rnd.nextInt(startCp, cps + 1)
        return s.substring(s.offsetByCodePoints(0, startCp), s.offsetByCodePoints(0, endCp))
    }

    private fun randomQuery(rnd: Random, items: List<Item>): String {
        val r = rnd.nextInt(100)
        if (r < 50 && items.isNotEmpty()) {
            val it = items.random(rnd)
            val fields = listOf(it.content, it.authorName, it.authorId)
            val f = fields.random(rnd)
            if (f.isEmpty()) return listOf("", " ", "a").random(rnd)
            var q = randomSubstring(rnd, f)
            q = when (rnd.nextInt(10)) {
                in 0..2 -> q.uppercase()
                in 3..5 -> q.lowercase()
                else -> q
            }
            if (rnd.nextInt(10) < 3) {
                val pad = listOf(" ", "  ", "\t", "\n", "\u0000", "\u000B")
                q = pad.random(rnd) + q + pad.random(rnd)
            }
            return q
        }
        return when {
            r < 60 -> listOf("", " ", "   ", "\t", "\n", "\u0000\u001F", "\u0085", "\u00A0", "\u2007", "\u202F", "\u200B", " \u0085 ", "\u00A0\u00A0").random(rnd)
            r < 75 -> words.random(rnd)
            r < 90 -> randomField(rnd)
            else -> (0 until rnd.nextInt(1, 5)).joinToString("") { edgeCodePoints.random(rnd) }
        }
    }

    @Test
    fun matchesRandomOracle() {
        val rnd = Random(43)
        repeat(300) {
            val item = randomItem(rnd)
            val query = randomQuery(rnd, listOf(item))
            assertEquals(
                oracleMatches(item, query),
                ExploreSearchPolicy.matches(item.content, item.authorName, item.authorId, query),
                "item=$item query=<$query>"
            )
        }
    }

    @Test
    fun filterRandomOracle() {
        val rnd = Random(43)
        repeat(200) {
            val items = List(rnd.nextInt(0, 7)) { randomItem(rnd) }
            val query = randomQuery(rnd, items)
            val got = ExploreSearchPolicy.filter(items, query, { it.content }, { it.authorName }, { it.authorId })
            assertEquals(oracleFilter(items, query), got, "query=<$query> items=$items")
            // Result is always a subsequence of the input, in order.
            var idx = 0
            for (kept in got) {
                var found = false
                while (idx < items.size) {
                    if (items[idx] == kept) {
                        found = true
                        idx++
                        break
                    }
                    idx++
                }
                assertTrue(found, "kept $kept is not a subsequence of $items")
            }
        }
    }

    @Test
    fun caseFoldingRandomOracle() {
        val rnd = Random(43)
        repeat(300) {
            val hay = (0 until rnd.nextInt(1, 9)).joinToString("") { edgeCodePoints.random(rnd) }
            val needle = randomSubstring(rnd, hay).let {
                when (rnd.nextInt(3)) {
                    0 -> it.uppercase()
                    1 -> it.lowercase()
                    else -> it
                }
            }
            val item = Item(hay, "x", "u1")
            assertEquals(
                oracleMatches(item, needle),
                ExploreSearchPolicy.matches(item.content, item.authorName, item.authorId, needle),
                "hay=<$hay> needle=<$needle>"
            )
        }
    }

    @Test
    fun queryPaddingRandomOracle() {
        val rnd = Random(43)
        repeat(200) {
            val items = List(rnd.nextInt(0, 7)) { randomItem(rnd) }
            val core = randomQuery(rnd, items)
            val pad = listOf(" ", "  ", "\t", "\n", "\u000B", "\u0000", "\u0085", "\u00A0", "\u2007")
            val query = pad.random(rnd) + core + pad.random(rnd)
            val first = items.firstOrNull() ?: Item("", "", "")
            assertEquals(
                oracleMatches(first, query),
                ExploreSearchPolicy.matches(first.content, first.authorName, first.authorId, query),
                "matches must use the raw query item=$first query=<$query>"
            )
            assertEquals(
                oracleFilter(items, query),
                ExploreSearchPolicy.filter(items, query, { it.content }, { it.authorName }, { it.authorId }),
                "filter must use the trimmed query query=<$query> items=$items"
            )
        }
    }

    @Test
    fun matchesDoesNotTrimButFilterDoes() {
        val items = listOf(
            Item("alice", "Bob", "u1"),
            Item("x\u00A0y", "Carol", "u2"),
            Item("q\u0085z", "Dave", "u3")
        )
        // Empty query matches every field; a longer query never matches.
        assertTrue(ExploreSearchPolicy.matches("alice", "Bob", "u1", ""))
        assertFalse(ExploreSearchPolicy.matches("alice", "Bob", "u1", "alicealicealice"))
        // matches() sees the padding, filter() does not.
        assertFalse(ExploreSearchPolicy.matches("alice", "Bob", "u1", " alice "))
        assertFalse(ExploreSearchPolicy.matches("alice", "Bob", "u1", "\talice\n"))
        assertEquals(listOf(items[0]), ExploreSearchPolicy.filter(items, "  alice  ", { it.content }, { it.authorName }, { it.authorId }))
        assertEquals(listOf(items[0]), ExploreSearchPolicy.filter(items, "ALICE", { it.content }, { it.authorName }, { it.authorId }))
        // NBSP is Kotlin whitespace, so filter() treats it as a blank query; NEL is
        // not whitespace, so it stays a real search.
        assertTrue(ExploreSearchPolicy.matches("x\u00A0y", "Carol", "u2", "\u00A0"))
        assertEquals(items, ExploreSearchPolicy.filter(items, "\u00A0", { it.content }, { it.authorName }, { it.authorId }))
        assertTrue(ExploreSearchPolicy.matches("q\u0085z", "Dave", "u3", "\u0085"))
        assertEquals(listOf(items[2]), ExploreSearchPolicy.filter(items, "\u0085", { it.content }, { it.authorName }, { it.authorId }))
        assertEquals(items, ExploreSearchPolicy.filter(items, "   ", { it.content }, { it.authorName }, { it.authorId }))
        assertEquals(emptyList(), ExploreSearchPolicy.filter(items, "zzz", { it.content }, { it.authorName }, { it.authorId }))
    }
}
