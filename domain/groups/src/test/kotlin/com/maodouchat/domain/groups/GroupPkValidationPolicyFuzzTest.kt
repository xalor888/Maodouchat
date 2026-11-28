package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [GroupPkValidationPolicy] (checklist line 931,
 * 41st block). Test-only, zero production changes.
 *
 * The oracle derives validity independently of production: it hard-codes the
 * `Character.isWhitespace` set and strips only chars `<= ' '` from both ends.
 * Three traps it nails down, all verified offline before this file was written:
 * - `trim()` strips only `<= ' '`, while `isNotBlank()` uses Java whitespace:
 *   U+2003/U+3000/U+1680/U+2028/U+2029/U+205F/U+2000-U+2006/U+2008-U+200A make
 *   a title non-blank but are NOT trimmed, so 120 x's wrapped in them count
 *   122 and are rejected — a Unicode-aware trim model says "valid" and is wrong;
 * - NBSP (U+00A0), FIGURE SPACE (U+2007) and NNBSP (U+202F) are NOT whitespace
 *   at all (`Character.isWhitespace` excludes non-breaking spaces), so a lone
 *   NBSP is a *valid* title — as is a lone NEL (U+0085), which the javadoc
 *   lists as whitespace but the JDK implementation returns false for;
 * - zero-width chars (ZWSP U+200B, BOM U+FEFF) and control chars `<= ' '`
 *   (NUL, U+0001) are not whitespace either: a lone NUL is valid, and NUL
 *   *does* trim, so NUL + 120 x's is exactly 120 and valid.
 * A Python mini-model differentially checks the oracle against the production
 * expression re-run on the real JVM (Java probe) on 5000 random inputs
 * (0 diffs); the probe also verifies the hand-written whitespace set against
 * `Character.isWhitespace` over the whole BMP (0 diffs). The naive
 * Unicode-trim model disagrees with production on 331 of those 5000 inputs.
 */
class GroupPkValidationPolicyFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 300

        // Mirrors the private production limit (GroupPkValidationPolicy keeps it
        // private, so the tests pin the value rather than import it).
        private const val MAX_TITLE_LENGTH = 120

        private val ALPHABET = charArrayOf(
            'x', 'a', 'Z', '0', 'é',
            ' ', '\t', '\n', '\u000B', '\u000C', '\r',
            '\u0000', '\u0001', '\u0007', '\u001C', '\u001D', '\u001E', '\u001F',
            '\u0085', '\u00A0', '\u1680',
            '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006',
            '\u2007', '\u2008', '\u2009', '\u200A',
            '\u200B', '\u200C', '\u200D',
            '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
            '\uFEFF', '\u00AD'
        )

        private val AFFIX_ALPHABET = charArrayOf(
            ' ', '\t', '\n', '\r', '\u0000', '\u001C', '\u001F',
            '\u0085', '\u00A0', '\u1680', '\u2003', '\u2007', '\u200B', '\u2028',
            '\u202F', '\u205F', '\u3000', '\uFEFF'
        )
    }

    // Character.isWhitespace as implemented by the JDK: U+0085 is listed in
    // the javadoc but returns false, and the non-breaking spaces U+00A0 /
    // U+2007 / U+202F are explicitly excluded. Verified exhaustively over the
    // BMP by the Java probe before this file was written.
    private fun isJavaWhitespace(c: Char): Boolean =
        c in '\u0009'..'\u000D' || c == ' ' || c in '\u001C'..'\u001F' ||
            c == '\u1680' || c in '\u2000'..'\u2006' || c in '\u2008'..'\u200A' ||
            c == '\u2028' || c == '\u2029' || c == '\u205F' || c == '\u3000'

    private fun trimmedLength(title: String): Int {
        var start = 0
        var end = title.length
        while (start < end && title[start] <= ' ') start++
        while (end > start && title[end - 1] <= ' ') end--
        return end - start
    }

    private fun oracleValid(title: String): Boolean =
        title.any { !isJavaWhitespace(it) } && trimmedLength(title) <= MAX_TITLE_LENGTH

    // The obvious-but-wrong model: trim with the Java whitespace set, as a
    // Unicode-aware trim would. It disagrees with production wherever a
    // whitespace char survives trim() or a control char is trimmed.
    private fun naiveValid(title: String): Boolean {
        if (title.none { !isJavaWhitespace(it) }) return false
        var start = 0
        var end = title.length
        while (start < end && isJavaWhitespace(title[start])) start++
        while (end > start && isJavaWhitespace(title[end - 1])) end--
        return end - start <= MAX_TITLE_LENGTH
    }

    private fun randomTitle(random: Random): String {
        val length = when (random.nextInt(5)) {
            0 -> random.nextInt(0, 11)
            1 -> random.nextInt(100, 136)
            2 -> random.nextInt(115, 126)
            3 -> random.nextInt(0, 136)
            else -> random.nextInt(0, 4)
        }
        return buildString(length) {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.size)]) }
        }
    }

    private fun nearBoundaryTitle(random: Random): String {
        val coreLength = random.nextInt(115, 126)
        val left = buildString {
            repeat(random.nextInt(0, 4)) { append(AFFIX_ALPHABET[random.nextInt(AFFIX_ALPHABET.size)]) }
        }
        val right = buildString {
            repeat(random.nextInt(0, 4)) { append(AFFIX_ALPHABET[random.nextInt(AFFIX_ALPHABET.size)]) }
        }
        return left + "x".repeat(coreLength) + right
    }

    @Test
    fun `random titles match oracle`() {
        val random = Random(0x41_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val title = randomTitle(random)
            assertEquals(
                oracleValid(title),
                GroupPkValidationPolicy.isValidTitle(title),
                "fuzz 迭代 #$i 标题码点=${title.map { it.code }}"
            )
        }
    }

    @Test
    fun `random near-boundary titles match oracle`() {
        val random = Random(0x42_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val title = nearBoundaryTitle(random)
            assertEquals(
                oracleValid(title),
                GroupPkValidationPolicy.isValidTitle(title),
                "边界 fuzz 迭代 #$i 标题码点=${title.map { it.code }}"
            )
        }
    }

    @Test
    fun `random title pairs match oracle`() {
        val random = Random(0x43_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val left = randomTitle(random)
            val right = randomTitle(random)
            assertEquals(
                oracleValid(left) && oracleValid(right),
                GroupPkValidationPolicy.isValidTitles(left, right),
                "成对 fuzz 迭代 #$i 左=${left.map { it.code }} 右=${right.map { it.code }}"
            )
        }
    }

    @Test
    fun `unicode whitespace only titles are blank`() {
        // Each of these is whitespace per Character.isWhitespace, so the
        // title is blank and rejected — even though trim() would not strip
        // most of them either.
        val blanks = listOf(
            "\u2003", "\u3000", "\u1680", "\u2028", "\u2029",
            "\u205F", "\u2000", "\u200A", "\u001C", "\u001F",
            "\u2003\u3000\u1680\u2028"
        )
        for (title in blanks) {
            assertFalse(
                GroupPkValidationPolicy.isValidTitle(title),
                "纯空白标题应被拒绝：码点=${title.map { it.code }}"
            )
        }
    }

    @Test
    fun `non-breaking spaces and NEL are not blank`() {
        // NBSP / FIGURE SPACE / NNBSP are explicitly excluded from Java
        // whitespace, and NEL is listed in the javadoc but false in the JDK
        // implementation — a lone one of these is a valid title.
        val validSingles = listOf("\u00A0", "\u2007", "\u202F", "\u0085")
        for (title in validSingles) {
            assertTrue(
                GroupPkValidationPolicy.isValidTitle(title),
                "单个非断行空白应为有效标题：码点=${title.map { it.code }}"
            )
        }
        assertTrue(
            GroupPkValidationPolicy.isValidTitle("\u2003\u00A0\u3000\u0085\u2028"),
            "混入 NBSP/NEL 的串不再是纯空白，应为有效标题"
        )
    }

    @Test
    fun `zero-width and invisible non-whitespace chars are not blank`() {
        val validSingles = listOf("\u200B", "\uFEFF", "\u200C", "\u200D", "\u00AD")
        for (title in validSingles) {
            assertTrue(
                GroupPkValidationPolicy.isValidTitle(title),
                "单个零宽/不可见非空白字符应为有效标题：码点=${title.map { it.code }}"
            )
        }
    }

    @Test
    fun `trim only strips chars at most space`() {
        // Unicode spaces around 120 x's survive trim(): 122 chars, rejected.
        val notTrimmed = listOf(
            "\u2003" + "x".repeat(120) + "\u2003",
            "\u00A0" + "x".repeat(120),
            "\u3000" + "x".repeat(120) + "\u3000",
            "\u0085" + "x".repeat(120),
            "\u200B" + "x".repeat(120),
            "\uFEFF" + "x".repeat(120)
        )
        for (title in notTrimmed) {
            assertFalse(
                GroupPkValidationPolicy.isValidTitle(title),
                "未被 trim 的环绕字符应计入长度：首码点=${title.first().code}"
            )
        }
        // ASCII whitespace and control chars <= ' ' do strip: exactly 120.
        val trimmed = listOf(
            " " + "x".repeat(120) + " ",
            "\t" + "x".repeat(120) + "\n",
            "\u0000" + "x".repeat(120),
            "\u001C" + "x".repeat(120) + "\u001F"
        )
        for (title in trimmed) {
            assertTrue(
                GroupPkValidationPolicy.isValidTitle(title),
                "可被 trim 的环绕字符不应计入长度：首码点=${title.first().code}"
            )
        }
    }

    @Test
    fun `boundary is strict after trim`() {
        assertTrue(GroupPkValidationPolicy.isValidTitle("x".repeat(119)), "119 字有效")
        assertTrue(GroupPkValidationPolicy.isValidTitle("x".repeat(120)), "120 字有效")
        assertFalse(GroupPkValidationPolicy.isValidTitle("x".repeat(121)), "121 字无效")
        assertTrue(GroupPkValidationPolicy.isValidTitle(" " + "x".repeat(120)), "空格 + 120 字有效")
        assertFalse(GroupPkValidationPolicy.isValidTitle(" " + "x".repeat(121)), "空格 + 121 字无效")
        assertTrue(
            GroupPkValidationPolicy.isValidTitle("\u2003" + "x".repeat(118) + "\u2003"),
            "EM SPACE + 118 字 + EM SPACE 恰 120 字，有效"
        )
        assertFalse(
            GroupPkValidationPolicy.isValidTitle("\u2003" + "x".repeat(119) + "\u2003"),
            "EM SPACE + 119 字 + EM SPACE 共 121 字，无效"
        )
        assertTrue(GroupPkValidationPolicy.isValidTitle("\u00A0" + "x".repeat(119)), "NBSP + 119 字恰 120 字，有效")
        assertFalse(GroupPkValidationPolicy.isValidTitle("\u00A0" + "x".repeat(120)), "NBSP + 120 字共 121 字，无效")
        assertTrue(GroupPkValidationPolicy.isValidTitle("\u0085" + "x".repeat(119)), "NEL + 119 字恰 120 字，有效")
        assertTrue(GroupPkValidationPolicy.isValidTitle("\u202F" + "x".repeat(119)), "NNBSP + 119 字恰 120 字，有效")
        assertTrue(GroupPkValidationPolicy.isValidTitle("\u2007" + "x".repeat(119)), "FIGURE SPACE + 119 字恰 120 字，有效")
    }

    @Test
    fun `naive unicode-trim model disagrees`() {
        // Naive trims Unicode whitespace, so it says "valid" where production
        // counts the surviving spaces and rejects.
        val naiveSaysValid = listOf(
            "\u2003" + "x".repeat(120) + "\u2003",
            "\u3000" + "x".repeat(120) + "\u3000",
            "\u2003" + "x".repeat(119) + "\u2003"
        )
        for (title in naiveSaysValid) {
            assertTrue(naiveValid(title), "naive 模型应判有效：首码点=${title.first().code}")
            assertFalse(
                GroupPkValidationPolicy.isValidTitle(title),
                "生产侧应判无效：首码点=${title.first().code}"
            )
        }
        // Naive does not trim control chars, so it says "invalid" where
        // production trims them to exactly 120 and accepts.
        val naiveSaysInvalid = listOf(
            "\u0000" + "x".repeat(120),
            "\u0001" + "x".repeat(120)
        )
        for (title in naiveSaysInvalid) {
            assertFalse(naiveValid(title), "naive 模型应判无效：首码点=${title.first().code}")
            assertTrue(
                GroupPkValidationPolicy.isValidTitle(title),
                "生产侧应判有效：首码点=${title.first().code}"
            )
        }
    }

    @Test
    fun `control chars below space trim but are not blank`() {
        val validSingles = listOf("\u0000", "\u0001", "\u0007")
        for (title in validSingles) {
            assertTrue(
                GroupPkValidationPolicy.isValidTitle(title),
                "单个 <= ' ' 的控制字符不是空白、且 trim 后长度为 0，应有效：码点=${title.map { it.code }}"
            )
        }
        assertTrue(GroupPkValidationPolicy.isValidTitle("\u0001" + "x".repeat(120)), "控制字符 + 120 字有效")
        assertFalse(GroupPkValidationPolicy.isValidTitle("\u0001" + "x".repeat(121)), "控制字符 + 121 字无效")
    }

    @Test
    fun `both-sides pairs with quirk chars`() {
        assertTrue(GroupPkValidationPolicy.isValidTitles("a", "b"), "两侧普通标题有效")
        assertFalse(GroupPkValidationPolicy.isValidTitles("\u2003", "b"), "左侧纯 EM SPACE 无效")
        assertTrue(GroupPkValidationPolicy.isValidTitles("a", "\u00A0"), "右侧单个 NBSP 有效")
        assertTrue(GroupPkValidationPolicy.isValidTitles("\u200B", "\uFEFF"), "两侧零宽字符均有效")
        assertFalse(
            GroupPkValidationPolicy.isValidTitles("x".repeat(120), "\u2003" + "x".repeat(120)),
            "右侧 EM SPACE + 120 字共 121 字，无效"
        )
        assertTrue(
            GroupPkValidationPolicy.isValidTitles("\u2003" + "x".repeat(118) + "\u2003", "x"),
            "左侧恰 120 字，右侧普通标题，有效"
        )
        assertFalse(GroupPkValidationPolicy.isValidTitles("", "a"), "左侧空串无效")
        assertFalse(GroupPkValidationPolicy.isValidTitles("x".repeat(121), "a"), "左侧 121 字无效")
    }
}
