package com.maodouchat.domain.conversation

import com.maodouchat.core.model.MessageType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [MessagePinPolicy] (checklist line 931, 46th block).
 * Test-only, zero production changes.
 *
 * The oracle reimplements the policy independently: an explicit 9-type
 * pinnable set, a group gate with ASCII-only role folding, an explicit
 * preview-kind mapping table, and a hand-rolled textPreview (explicit
 * backward scan for the last "<meta>", explicit trim/blank with the exact
 * Kotlin whitespace set, explicit newline fold, explicit take() that throws
 * IllegalArgumentException for negative counts exactly like stdlib).
 * Shared primitives (production calls them too, so sharing is exact):
 * String.length and char indexing.
 *
 * Two deliberate divergences, both pinned, not fixed:
 * - Role folding: production uses String.uppercase() (default locale,
 *   Unicode-aware), the oracle folds ASCII only. On ASCII roles they agree;
 *   on non-ASCII roles production folds more: dotless-i "admın" uppercases
 *   to "ADMIN" and can pin, while fullwidth "ＡDMIN", dotted "admİn",
 *   long-s "oſwner" and sharp-s "ADMINß" still cannot.
 * - maxLen <= 0 on non-blank text THROWS IllegalArgumentException
 *   (take(maxLen - 1) with a negative count); it does not yield "…".
 *   Blank text short-circuits to "" before take() and is fine.
 *
 * Whitespace facts (swept over all BMP chars on JDK 17, stdlib 2.0.21 and
 * 2.4.0 — identical): Kotlin trim()/isBlank() use the set U+0009..U+000D,
 * U+001C..U+0020, U+00A0, U+1680, U+2000..U+200A, U+2028..U+2029, U+202F,
 * U+205F, U+3000 — NOT U+0085 NEL, NOT ZWSP/BOM. Truncation counts UTF-16
 * units, so take() can split a surrogate pair.
 *
 * Verification (offline, JDK 17): the real production code was compiled
 * verbatim with the Kotlin compiler and differentially checked against this
 * oracle on 199,067 cases — the exact inputs these tests generate
 * (Random(42)), an exhaustive canPin sweep (13 types x group/direct x 25
 * roles), limit/kind exhaustives, a 196,608-case whitespace sweep (every
 * BMP char alone and wrapped, lone surrogates included), and a meta/maxLen
 * sweep — with 0 differences except the 9 designed "admın" cases above
 * (1 role x 9 pinnable types). Every pinned expectation below was checked
 * against real production. The 5 test methods were also run locally
 * against the real production code (kotlin-test stubs): all pass.
 */
class MessagePinPolicyFuzzTest {


    // ---------- whitespace set (empirically swept, see header) ----------

    private fun isWs(c: Char): Boolean = when (c.code) {
        in 0x09..0x0D, in 0x1C..0x20 -> true
        0x00A0, 0x1680, 0x202F, 0x205F, 0x3000 -> true
        in 0x2000..0x200A, in 0x2028..0x2029 -> true
        else -> false
    }

    private fun asciiUpper(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) out.append(if (c in 'a'..'z') (c.code - 32).toChar() else c)
        return out.toString()
    }

    // ---------- oracles ----------

    private val PINNABLE_TYPES = setOf(
        MessageType.TEXT, MessageType.MARKDOWN, MessageType.IMAGE, MessageType.GIF, MessageType.STICKER,
        MessageType.LOCATION, MessageType.VOICE, MessageType.VIDEO, MessageType.FILE
    )

    private fun isPinnableTypeOracle(type: MessageType): Boolean = type in PINNABLE_TYPES

    private fun canPinOracle(isGroup: Boolean, myRole: String?, type: MessageType): Boolean {
        if (!isPinnableTypeOracle(type)) return false
        if (!isGroup) return true
        val role = asciiUpper(myRole ?: "")
        return role == "OWNER" || role == "ADMIN"
    }

    private fun wouldExceedLimitOracle(currentCount: Int, alreadyPinned: Boolean): Boolean {
        if (alreadyPinned) return false
        return currentCount >= 20
    }

    private fun previewKindOracle(type: MessageType): String = when (type) {
        MessageType.TEXT, MessageType.MARKDOWN -> "TEXT"
        MessageType.IMAGE, MessageType.GIF -> "IMAGE"
        MessageType.VOICE -> "VOICE"
        MessageType.VIDEO -> "VIDEO"
        MessageType.FILE -> "FILE"
        MessageType.LOCATION -> "LOCATION"
        MessageType.STICKER -> "STICKER"
        else -> "GENERIC"
    }

    private fun lastMetaIndex(s: String): Int {
        // Last start index of the literal "<meta>", or -1. Explicit backward scan.
        val lit = "<meta>"
        var i = s.length - lit.length
        while (i >= 0) {
            var ok = true
            for (j in lit.indices) if (s[i + j] != lit[j]) { ok = false; break }
            if (ok) return i
            i--
        }
        return -1
    }

    private fun trimOracle(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isWs(s[start])) start++
        while (end > start && isWs(s[end - 1])) end--
        return s.substring(start, end)
    }

    private fun isBlankOracle(s: String): Boolean {
        for (c in s) if (!isWs(c)) return false
        return true
    }

    private fun foldNewlines(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) out.append(if (c == '\n') ' ' else c)
        return out.toString()
    }

    private fun takeOracle(s: String, n: Int): String {
        if (n < 0) throw IllegalArgumentException("Requested character count $n is less than zero.")
        val end = if (n < s.length) n else s.length
        val out = StringBuilder(end)
        for (i in 0 until end) out.append(s[i])
        return out.toString()
    }

    private fun textPreviewOracle(content: String, maxLen: Int): String {
        val cut = lastMetaIndex(content)
        val before = if (cut < 0) content else content.substring(0, cut)
        val plain = foldNewlines(trimOracle(before))
        if (isBlankOracle(plain)) return ""
        return if (plain.length <= maxLen) plain else takeOracle(plain, maxLen - 1) + "…"
    }

    // ---------- generators (Random(42) in the CI tests) ----------

    private val ROLE_POOL = listOf<String?>(
        null, "", "OWNER", "ADMIN", "MEMBER", "owner", "admin", "Admin", "aDmIn", "oWnEr",
        " OWNER", "OWNER ", "ADMIN ", "\tOWNER", "ADMIN\t", "ADMINX", "SUPERADMIN", "OWNER2",
        "   ", "\t", "member", "Member", "OWNER_", "_OWNER", "ADMIN-1", "owners", "admins"
    )

    private fun randomRole(rnd: Random): String? {
        if (rnd.nextInt(4) == 0) {
            // Random ASCII role, 0..8 chars from letters/digits/space/underscore.
            val alphabet = "abcdeABCDE012 _\t-"
            val len = rnd.nextInt(0, 9)
            val sb = StringBuilder()
            repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
            return sb.toString()
        }
        return ROLE_POOL[rnd.nextInt(ROLE_POOL.size)]
    }

    private fun randomType(rnd: Random): MessageType = MessageType.entries[rnd.nextInt(MessageType.entries.size)]

    private val COUNT_POOL = listOf(
        Int.MIN_VALUE, Int.MAX_VALUE, -100, -1, 0, 1, 18, 19, 20, 21, 22, 100, 1000
    )

    private fun randomCount(rnd: Random): Int {
        return if (rnd.nextInt(3) == 0) COUNT_POOL[rnd.nextInt(COUNT_POOL.size)]
        else rnd.nextInt(-5, 26)
    }

    private val CONTENT_PIECES = listOf(
        "a", "hello", "你好", "x", " ", "  ", "\n", "\t", "\r", "<meta>", "<meta",
        "meta>", "<", ">", "…", "\u00A0", "\u2007", "\u202F", "\u0085", "\u200B",
        "\uFEFF", "\uD83D\uDE00", "a".repeat(50), "b".repeat(100), "{\"a\":1}",
        "word ", "a\nb", "a\rb", "ab", "  pad  ", "x".repeat(200)
    )

    private val MAXLEN_POOL = listOf(-5, -2, -1, 0, 1, 2, 3, 5, 48, 95, 96, 97, 200)

    private fun randomContent(rnd: Random): String {
        val n = rnd.nextInt(0, 7)
        val sb = StringBuilder()
        repeat(n) { sb.append(CONTENT_PIECES[rnd.nextInt(CONTENT_PIECES.size)]) }
        return sb.toString()
    }

    private fun randomMaxLen(rnd: Random): Int {
        return if (rnd.nextInt(3) == 0) MAXLEN_POOL[rnd.nextInt(MAXLEN_POOL.size)]
        else rnd.nextInt(-2, 121)
    }

    @Test
    fun canPinRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val isGroup = rnd.nextBoolean()
            val role = randomRole(rnd)
            val type = randomType(rnd)
            assertEquals(
                canPinOracle(isGroup, role, type),
                MessagePinPolicy.canPin(isGroup, role, type),
                "isGroup=$isGroup role=<$role> type=$type"
            )
        }
    }

    @Test
    fun wouldExceedLimitRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val count = randomCount(rnd)
            val alreadyPinned = rnd.nextBoolean()
            assertEquals(
                wouldExceedLimitOracle(count, alreadyPinned),
                MessagePinPolicy.wouldExceedLimit(count, alreadyPinned),
                "count=$count alreadyPinned=$alreadyPinned"
            )
        }
    }

    @Test
    fun previewKindRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val type = randomType(rnd)
            assertEquals(
                PreviewKind.valueOf(previewKindOracle(type)),
                MessagePinPolicy.previewKind(type),
                "type=$type"
            )
        }
    }

    @Test
    fun textPreviewRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val content = randomContent(rnd)
            val maxLen = randomMaxLen(rnd)
            val expected = try {
                "S:" + textPreviewOracle(content, maxLen)
            } catch (e: IllegalArgumentException) {
                "THROW"
            }
            val actual = try {
                "S:" + MessagePinPolicy.textPreview(content, maxLen)
            } catch (e: IllegalArgumentException) {
                "THROW"
            }
            assertEquals(expected, actual, "content=<$content> maxLen=$maxLen")
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // maxLen <= 0 on non-blank text throws (take() rejects negative counts).
        assertFailsWith<IllegalArgumentException> { MessagePinPolicy.textPreview("hello", 0) }
        assertFailsWith<IllegalArgumentException> { MessagePinPolicy.textPreview("hello", -1) }
        assertFailsWith<IllegalArgumentException> { MessagePinPolicy.textPreview("hello", -5) }
        // Blank text short-circuits to "" before take(), so maxLen <= 0 is fine.
        assertEquals("", MessagePinPolicy.textPreview("", 0))
        assertEquals("", MessagePinPolicy.textPreview("   ", 0))
        // Truncation budget.
        assertEquals("…", MessagePinPolicy.textPreview("hello", 1))
        assertEquals("h…", MessagePinPolicy.textPreview("hello", 2))
        assertEquals("he…", MessagePinPolicy.textPreview("hello", 3))
        assertEquals("a".repeat(96), MessagePinPolicy.textPreview("a".repeat(96), 96))
        assertEquals("a".repeat(95) + "…", MessagePinPolicy.textPreview("a".repeat(97), 96))
        // <meta>: the last occurrence wins; an unclosed "<meta" stays literal.
        assertEquals("hello", MessagePinPolicy.textPreview("hello<meta>{\"a\":1}"))
        assertEquals("a<meta>b", MessagePinPolicy.textPreview("a<meta>b<meta>c"))
        assertEquals("a<meta", MessagePinPolicy.textPreview("a<meta"))
        assertEquals("", MessagePinPolicy.textPreview("<meta>{\"a\":1}"))
        assertEquals("a<meta>b", MessagePinPolicy.textPreview("a<meta>b<meta>"))
        // Whitespace: NBSP is blank (Kotlin isWhitespace), NEL is not.
        assertEquals("", MessagePinPolicy.textPreview("\u00A0"))
        assertEquals("\u0085", MessagePinPolicy.textPreview("\u0085"))
        assertEquals("\u0085", MessagePinPolicy.textPreview(" \u0085 "))
        // Only '\n' folds to a space; '\r' survives.
        assertEquals("a\r b", MessagePinPolicy.textPreview("a\r\nb"))
        assertEquals("a\rb", MessagePinPolicy.textPreview("a\rb"))
        // Truncation counts UTF-16 units: take() can split a surrogate pair.
        assertEquals(
            "\uD83D\uDE00\uD83D…",
            MessagePinPolicy.textPreview("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00", 4)
        )
        assertEquals(
            "\uD83D\uDE00\uD83D\uDE00…",
            MessagePinPolicy.textPreview("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00", 5)
        )
        assertEquals(
            "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00",
            MessagePinPolicy.textPreview("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00", 6)
        )
        // A lone surrogate passes through untouched.
        assertEquals("a\uD800b", MessagePinPolicy.textPreview("a\uD800b"))
        assertEquals("a…", MessagePinPolicy.textPreview("a\uD800b", 2))
        // Role folding is locale-sensitive Unicode uppercase: dotless-i folds to I.
        assertTrue(MessagePinPolicy.canPin(true, "admın", MessageType.TEXT))
        // Fullwidth A / dotted capital I / long-s / sharp-s do not fold to ASCII.
        assertFalse(MessagePinPolicy.canPin(true, "ＡDMIN", MessageType.TEXT))
        assertFalse(MessagePinPolicy.canPin(true, "admİn", MessageType.TEXT))
        assertFalse(MessagePinPolicy.canPin(true, "oſwner", MessageType.TEXT))
        assertFalse(MessagePinPolicy.canPin(true, "ADMINß", MessageType.TEXT))
        // Group gate + limit + kind spot checks.
        assertTrue(MessagePinPolicy.canPin(true, "owner", MessageType.FILE))
        assertFalse(MessagePinPolicy.canPin(true, "ADMINX", MessageType.TEXT))
        assertFalse(MessagePinPolicy.canPin(true, "   ", MessageType.TEXT))
        assertFalse(MessagePinPolicy.canPin(false, "MEMBER", MessageType.SYSTEM))
        assertTrue(MessagePinPolicy.wouldExceedLimit(20, alreadyPinned = false))
        assertFalse(MessagePinPolicy.wouldExceedLimit(19, alreadyPinned = false))
        assertFalse(MessagePinPolicy.wouldExceedLimit(20, alreadyPinned = true))
        assertTrue(MessagePinPolicy.wouldExceedLimit(Int.MAX_VALUE, alreadyPinned = false))
        assertFalse(MessagePinPolicy.wouldExceedLimit(Int.MIN_VALUE, alreadyPinned = false))
        assertEquals(PreviewKind.GENERIC, MessagePinPolicy.previewKind(MessageType.SYSTEM))
        assertEquals(PreviewKind.IMAGE, MessagePinPolicy.previewKind(MessageType.GIF))
        assertEquals(PreviewKind.GENERIC, MessagePinPolicy.previewKind(MessageType.NUDGE))
    }

}
