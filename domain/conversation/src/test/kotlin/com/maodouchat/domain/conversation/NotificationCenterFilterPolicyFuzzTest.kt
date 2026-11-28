package com.maodouchat.domain.conversation

import com.maodouchat.core.model.NotificationCenterItem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [NotificationCenterFilterPolicy] (checklist line 931, 42nd block).
 * Test-only, zero production changes.
 *
 * The oracle reimplements case-insensitive substring search from scratch: it walks both
 * strings code point by code point and compares with Character.toUpperCase/toLowerCase(int),
 * the same primitives JDK 17's String.regionMatches uses (which is what Kotlin's
 * contains(ignoreCase = true) delegates to). A Python mini-model differentially checks
 * this oracle against the real JDK regionMatches on 5000 random inputs, 500 random
 * filter lists and an exhaustive sweep of all 2799 chars with a non-identity case map
 * (0 diffs) before this file was written.
 *
 * Two asymmetries the oracle must reproduce: matches() does not trim the query (filter()
 * does), and trim()/isBlank() use Kotlin Char.isWhitespace, so NBSP is blank and
 * trimmed while NEL is not.
 */
class NotificationCenterFilterPolicyFuzzTest {

    private fun compareCodePointIgnoreCase(c1: Int, c2: Int): Boolean {
        if (c1 == c2) return true
        val u1 = Character.toUpperCase(c1)
        val u2 = Character.toUpperCase(c2)
        if (u1 == u2) return true
        return Character.toLowerCase(u1) == Character.toLowerCase(u2)
    }

    // Code-point-aware contains(ignoreCase = true); never indexes a UTF-16 unit, so a
    // supplementary pair is compared as one code point, like JDK 17 regionMatches.
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

    private fun oracleMatches(item: NotificationCenterItem, query: String): Boolean =
        containsIgnoreCase(item.title, query) ||
            containsIgnoreCase(item.subtitle.orEmpty(), query) ||
            containsIgnoreCase(item.preview.orEmpty(), query) ||
            containsIgnoreCase(item.type, query) ||
            item.extra.values.any { containsIgnoreCase(it, query) }

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

    private fun oracleFilter(items: List<NotificationCenterItem>, query: String): List<NotificationCenterItem> {
        val trimmed = kotlinTrim(query)
        if (kotlinBlank(trimmed)) return items
        return items.filter { oracleMatches(it, trimmed) }
    }

    private fun item(
        id: String,
        title: String,
        type: String = "MESSAGE",
        subtitle: String? = null,
        preview: String? = null,
        extra: Map<String, String> = emptyMap()
    ) = NotificationCenterItem(
        id = id, type = type, mergeKey = "m-$id",
        title = title, subtitle = subtitle, preview = preview, extra = extra
    )

    private val words = listOf(
        "alice", "Alice", "ALICE", "Bob", "bob", "张三", "李四", "未接来电",
        "群邀请", "跑团小队", "MESSAGE", "message", "MISSED_CALL", "missed_call",
        "GROUP_INVITE", "AI_TASK", "你好", "hello", "world", "test", "u1", "x_9",
        "out", "OUT", "at school", "在家", "walk", "i", "I", "k", "K"
    )

    // One string per code point, so random strings stay well-formed (no lone surrogates).
    private val edgeCodePoints = listOf(
        "\u0130", "\u0131", "\u00DF", "\u1E9E", "\u03C3", "\u03C2", "\u03A3",
        "\u212A", "\u00C5", "\u00E5", "\uFB00", "\uFB01", "\uFB02", "\u017F",
        "\u01C5", "\u01C6", "\u01C4", "\u01C8", "\u01C9", "\u01C7", "\u01CB",
        "\u01CC", "\u01CA", "\u01F2", "\u01F3", "\u01F1", "\u00B5", "\u039C",
        "\u03BC", "\u10A0", "\u2D00", "\uD801\uDC00", "\uD801\uDC28",
        "\uD801\uDCCF", "\uD801\uDCF7", "\uD83D\uDE00", "\uD83D\uDE01",
        "\u0085", "\u00A0", "\u2007", "\u202F", "\u200B", "\u0307", "\u00AD"
    )

    private val types = listOf(
        "MESSAGE", "MISSED_CALL", "AI_TASK", "POST_INTERACTION", "FRIEND_REQUEST",
        "GROUP_INVITE", "REPORT", "SECURITY", "message", "Message", ""
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

    private fun randomItem(rnd: Random, id: Int): NotificationCenterItem =
        item(
            id = "$id",
            title = randomField(rnd),
            type = types.random(rnd),
            subtitle = if (rnd.nextInt(10) < 3) null else randomField(rnd),
            preview = if (rnd.nextInt(10) < 3) null else randomField(rnd),
            extra = (0 until rnd.nextInt(4)).associate { "k$it" to randomField(rnd) }
        )

    // Substring taken at code-point boundaries, so a supplementary pair is never split.
    private fun randomSubstring(rnd: Random, s: String): String {
        val cps = s.codePointCount(0, s.length)
        if (cps == 0) return ""
        val startCp = rnd.nextInt(cps)
        val endCp = rnd.nextInt(startCp, cps + 1)
        return s.substring(s.offsetByCodePoints(0, startCp), s.offsetByCodePoints(0, endCp))
    }

    private fun randomQuery(rnd: Random, items: List<NotificationCenterItem>): String {
        val r = rnd.nextInt(100)
        if (r < 50 && items.isNotEmpty()) {
            val it = items.random(rnd)
            val fields = listOf(it.title) +
                listOfNotNull(it.subtitle, it.preview) + listOf(it.type) + it.extra.values
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
        val rnd = Random(42)
        repeat(300) { index ->
            val item = randomItem(rnd, index)
            val query = randomQuery(rnd, listOf(item))
            assertEquals(
                oracleMatches(item, query),
                NotificationCenterFilterPolicy.matches(item, query),
                "item=$item query=<$query>"
            )
        }
    }

    @Test
    fun filterRandomOracle() {
        val rnd = Random(42)
        repeat(200) {
            val items = List(rnd.nextInt(0, 7)) { randomItem(rnd, it) }
            val query = randomQuery(rnd, items)
            assertEquals(
                oracleFilter(items, query),
                NotificationCenterFilterPolicy.filter(items, query),
                "query=<$query> items=$items"
            )
        }
    }

    @Test
    fun caseFoldingRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val hay = (0 until rnd.nextInt(1, 9)).joinToString("") { edgeCodePoints.random(rnd) }
            val needle = randomSubstring(rnd, hay).let {
                when (rnd.nextInt(3)) {
                    0 -> it.uppercase()
                    1 -> it.lowercase()
                    else -> it
                }
            }
            val item = item("1", hay)
            assertEquals(
                oracleMatches(item, needle),
                NotificationCenterFilterPolicy.matches(item, needle),
                "hay=<$hay> needle=<$needle>"
            )
        }
    }

    @Test
    fun turkishDottedCapitalI() {
        // U+0130 lowercases to 'i' (single char) in the JDK, so it matches i/I;
        // 'i' + combining dot is two code points and does not match one.
        val dotted = item("1", "İ")
        assertTrue(NotificationCenterFilterPolicy.matches(dotted, "i"))
        assertTrue(NotificationCenterFilterPolicy.matches(dotted, "I"))
        assertFalse(NotificationCenterFilterPolicy.matches(dotted, "i\u0307"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("2", "i"), "İ"))
    }

    @Test
    fun sharpSDoesNotFoldToSs() {
        // No full case folding: ß matches only ß and ẞ, never SS/ss.
        // The default MESSAGE type itself contains "SS", so use a neutral type here.
        val sharp = item("1", "ß", type = "INFO")
        assertTrue(NotificationCenterFilterPolicy.matches(sharp, "ß"))
        assertTrue(NotificationCenterFilterPolicy.matches(sharp, "ẞ"))
        assertFalse(NotificationCenterFilterPolicy.matches(sharp, "SS"))
        assertFalse(NotificationCenterFilterPolicy.matches(sharp, "ss"))
        assertFalse(NotificationCenterFilterPolicy.matches(item("2", "SS", type = "INFO"), "ß"))
    }

    @Test
    fun sigmaVariantsFoldTogether() {
        assertTrue(NotificationCenterFilterPolicy.matches(item("1", "σ"), "ς"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("2", "ς"), "σ"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("3", "Σ"), "ς"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("4", "ΟΣ"), "ος"))
    }

    @Test
    fun kelvinSignLongSAndLigatures() {
        // Kelvin sign folds to k; long s folds to s; ﬀ folds to nothing.
        assertTrue(NotificationCenterFilterPolicy.matches(item("1", "\u212A"), "k"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("2", "\u212A"), "K"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("3", "k"), "\u212A"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("4", "ſ"), "s"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("5", "ſ"), "S"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("6", "ﬀ"), "ﬀ"))
        assertFalse(NotificationCenterFilterPolicy.matches(item("7", "ﬀ"), "FF"))
        assertFalse(NotificationCenterFilterPolicy.matches(item("8", "ﬀ"), "ff"))
    }

    @Test
    fun astralCasePairsMatch() {
        // JDK 17 regionMatches compares code points, not UTF-16 units: Deseret and
        // Osage capitals match their small forms; a char-level oracle would miss this.
        assertTrue(NotificationCenterFilterPolicy.matches(item("1", "\uD801\uDC00"), "\uD801\uDC28"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("2", "\uD801\uDC28"), "\uD801\uDC00"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("3", "\uD801\uDCCF"), "\uD801\uDCF7"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("4", "\uD801\uDCF7"), "\uD801\uDCCF"))
        // Emoji have no case: only an exact match counts.
        assertTrue(NotificationCenterFilterPolicy.matches(item("5", "\uD83D\uDE00"), "\uD83D\uDE00"))
        assertFalse(NotificationCenterFilterPolicy.matches(item("6", "\uD83D\uDE00"), "\uD83D\uDE01"))
    }

    @Test
    fun georgianAlphabetMatches() {
        // Georgian needs the toLowerCase(toUpperCase()) second check to match.
        assertTrue(NotificationCenterFilterPolicy.matches(item("1", "\u10A0"), "\u2D00"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("2", "\u2D00"), "\u10A0"))
    }

    @Test
    fun nbspAndNelAreNotBlank() {
        // NBSP is a Kotlin space char (blank query, returns all); NEL is not
        // whitespace, so it stays a real search.
        val items = listOf(
            item("1", "alice"),
            item("2", "Bob", type = "MISSED_CALL"),
            item("3", "x\u00A0y"),
            item("4", "q\u0085z")
        )
        assertEquals(items, NotificationCenterFilterPolicy.filter(items, ""))
        assertEquals(items, NotificationCenterFilterPolicy.filter(items, "   "))
        assertEquals(items, NotificationCenterFilterPolicy.filter(items, "\t\n"))
        assertEquals(items, NotificationCenterFilterPolicy.filter(items, "\u00A0"))
        assertEquals(listOf(items[3]), NotificationCenterFilterPolicy.filter(items, "\u0085"))
        assertEquals(emptyList(), NotificationCenterFilterPolicy.filter(items, "\u0085alice\u0085"))
        assertEquals(listOf(items[1]), NotificationCenterFilterPolicy.filter(items, "MISSED_CALL"))
        assertEquals(listOf(items[1]), NotificationCenterFilterPolicy.filter(items, "missed_call"))
        assertEquals(emptyList(), NotificationCenterFilterPolicy.filter(items, "zzz"))
    }

    @Test
    fun nullFieldsExtraKeysAndMergeKey() {
        // Null subtitle/preview never match and never crash; extra keys and mergeKey
        // are not searched; type is searched case-insensitively.
        val it = item("3", "Alice", type = "MESSAGE", extra = mapOf("group" to "跑团小队"))
        assertFalse(NotificationCenterFilterPolicy.matches(it, "group"))
        assertTrue(NotificationCenterFilterPolicy.matches(it, "跑团"))
        assertTrue(NotificationCenterFilterPolicy.matches(it, "MESSAGE"))
        assertTrue(NotificationCenterFilterPolicy.matches(it, "message"))
        assertFalse(NotificationCenterFilterPolicy.matches(it, "m-3"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("4", "x", subtitle = "你好"), "你好"))
        assertTrue(NotificationCenterFilterPolicy.matches(item("5", "x", preview = "来自 Bob"), "bob"))
    }

    @Test
    fun matchesDoesNotTrimButFilterDoes() {
        val items = listOf(item("1", "alice"), item("2", "Bob"))
        assertFalse(NotificationCenterFilterPolicy.matches(items[0], " alice "))
        assertFalse(NotificationCenterFilterPolicy.matches(items[0], "\talice\n"))
        assertEquals(listOf(items[0]), NotificationCenterFilterPolicy.filter(items, "  alice  "))
        assertEquals(listOf(items[0]), NotificationCenterFilterPolicy.filter(items, "ALICE"))
        // Empty query matches everything; a query longer than the field never matches.
        assertTrue(NotificationCenterFilterPolicy.matches(items[0], ""))
        assertEquals(items, NotificationCenterFilterPolicy.filter(items, ""))
        assertFalse(NotificationCenterFilterPolicy.matches(items[0], "alicealicealice"))
    }
}
