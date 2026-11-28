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
 * Kotlin whitespace set and strips those chars from both ends.
 *
 * Kotlin 2.x `String.trim()`/`isBlank()` use `Char.isWhitespace`, which is
 * `Character.isWhitespace || Character.isSpaceChar`. Verified on JDK 21 with
 * an in-repo probe over the BMP, so:
 * - ASCII controls U+0009-U+000D and U+001C-U+001F trim; NUL/U+0001/U+0007 do
 *   NOT (they are neither Java whitespace nor space chars);
 * - U+2003/U+3000/U+1680/U+2028/U+2029/U+205F/U+2000-U+200A all trim, and the
 *   non-breaking spaces U+00A0/U+2007/U+202F also trim because isSpaceChar is
 *   true for them;
 * - NEL U+0085, ZWSP U+200B, ZWNJ/ZWJ, BOM U+FEFF and soft hyphen U+00AD are
 *   neither whitespace nor space chars: they neither trim nor make a title
 *   blank.
 * This differs from `java.lang.String.trim()`, which strips only `<= ' '`.
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

    // Kotlin Char.isWhitespace == Character.isWhitespace || Character.isSpaceChar.
    // Hard-coded so the oracle does not call the same predicate as production.
    private fun isKotlinWhitespace(c: Char): Boolean =
        c in '\u0009'..'\u000D' || c == ' ' || c in '\u001C'..'\u001F' ||
            c == '\u1680' || c in '\u2000'..'\u200A' ||
            c == '\u2028' || c == '\u2029' || c == '\u205F' || c == '\u3000' ||
            c == '\u00A0' || c == '\u2007' || c == '\u202F'

    private fun trimmedLength(title: String): Int {
        var start = 0
        var end = title.length
        while (start < end && isKotlinWhitespace(title[start])) start++
        while (end > start && isKotlinWhitespace(title[end - 1])) end--
        return end - start
    }

    private fun oracleValid(title: String): Boolean =
        title.any { !isKotlinWhitespace(it) } && trimmedLength(title) <= MAX_TITLE_LENGTH

    // The wrong model: java.lang.String.trim() semantics, which strip only
    // chars <= ' '. It disagrees with Kotlin trim() on NBSP and on controls
    // like NUL/U+0001 that Kotlin does not consider whitespace.
    private fun javaTrimValid(title: String): Boolean {
        var start = 0
        var end = title.length
        while (start < end && title[start] <= ' ') start++
        while (end > start && title[end - 1] <= ' ') end--
        val trimmed = title.substring(start, end)
        return title.any { !isKotlinWhitespace(it) } && trimmed.length <= MAX_TITLE_LENGTH
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
    fun `non-breaking spaces are blank but NEL is not`() {
        // Kotlin 的 isBlank 走 Char.isWhitespace，其中包含 Character.isSpaceChar，
        // 所以 NBSP / FIGURE SPACE / NNBSP 都算空白，单个出现也判无效。
        val blanks = listOf("\u00A0", "\u2007", "\u202F")
        for (title in blanks) {
            assertFalse(
                GroupPkValidationPolicy.isValidTitle(title),
                "单个非断行空白应判空白：码点=${title.map { it.code }}"
            )
        }
        // NEL 既不是 Java 空白也不是 SpaceChar，单个出现是有效标题。
        assertTrue(
            GroupPkValidationPolicy.isValidTitle("\u0085"),
            "单个 NEL 应为有效标题"
        )
        assertTrue(
            GroupPkValidationPolicy.isValidTitle("\u2003\u00A0\u3000\u0085\u2028"),
            "混入 NEL 的串不是纯空白，应为有效标题"
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
    fun `kotlin trim strips unicode spaces but not NUL`() {
        // Kotlin trim() 走 Char.isWhitespace，Unicode 空格同样会被剥掉，
        // 因此 120 个 x 两侧包 Unicode 空格仍是 120 字，有效。
        val trimmed = listOf(
            "\u2003" + "x".repeat(120) + "\u2003",
            "\u00A0" + "x".repeat(120),
            "\u3000" + "x".repeat(120) + "\u3000",
            "\u2007" + "x".repeat(120),
            "\u202F" + "x".repeat(120),
            " " + "x".repeat(120) + " ",
            "\t" + "x".repeat(120) + "\n",
            "\u001C" + "x".repeat(120) + "\u001F"
        )
        for (title in trimmed) {
            assertTrue(
                GroupPkValidationPolicy.isValidTitle(title),
                "可被 trim 的环绕字符不应计入长度：首码点=${title.first().code}"
            )
        }
        // NUL/U+0001/U+0007 既不是空白也不会被 trim 剥掉：环绕时计入长度。
        val notTrimmed = listOf(
            "\u0000" + "x".repeat(120),
            "\u0001" + "x".repeat(120),
            "\u0007" + "x".repeat(120),
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
    }

    @Test
    fun `boundary is strict after trim`() {
        assertTrue(GroupPkValidationPolicy.isValidTitle("x".repeat(119)), "119 字有效")
        assertTrue(GroupPkValidationPolicy.isValidTitle("x".repeat(120)), "120 字有效")
        assertFalse(GroupPkValidationPolicy.isValidTitle("x".repeat(121)), "121 字无效")
        assertTrue(GroupPkValidationPolicy.isValidTitle(" " + "x".repeat(120)), "空格 + 120 字有效")
        assertFalse(GroupPkValidationPolicy.isValidTitle(" " + "x".repeat(121)), "空格 + 121 字无效")
        // Unicode 空格会被 trim 剥掉，长度只看 x 的个数。
        assertTrue(
            GroupPkValidationPolicy.isValidTitle("\u2003" + "x".repeat(120) + "\u2003"),
            "EM SPACE + 120 字 + EM SPACE trim 后恰 120 字，有效"
        )
        assertFalse(
            GroupPkValidationPolicy.isValidTitle("\u2003" + "x".repeat(121) + "\u2003"),
            "EM SPACE + 121 字 + EM SPACE trim 后 121 字，无效"
        )
        assertTrue(GroupPkValidationPolicy.isValidTitle("\u00A0" + "x".repeat(120)), "NBSP + 120 字 trim 后 120 字，有效")
        assertFalse(GroupPkValidationPolicy.isValidTitle("\u00A0" + "x".repeat(121)), "NBSP + 121 字 trim 后 121 字，无效")
        assertTrue(GroupPkValidationPolicy.isValidTitle("\u0085" + "x".repeat(119)), "NEL + 119 字恰 120 字，有效")
        assertFalse(GroupPkValidationPolicy.isValidTitle("\u0085" + "x".repeat(120)), "NEL 不 trim，+ 120 字共 121 字，无效")
        assertTrue(GroupPkValidationPolicy.isValidTitle("\u202F" + "x".repeat(120)), "NNBSP + 120 字 trim 后 120 字，有效")
        assertTrue(GroupPkValidationPolicy.isValidTitle("\u2007" + "x".repeat(120)), "FIGURE SPACE + 120 字 trim 后 120 字，有效")
    }

    @Test
    fun `java-trim model disagrees with kotlin trim`() {
        // java.lang.String.trim() 只剥 <= ' '，Kotlin trim() 还剥 Unicode 空格：
        // 前者对「Unicode 空格 + 121 字」算 122 判无效，后者剥掉空格后 121 仍无效，
        // 但「Unicode 空格 + 120 字」java-trim 模型算 121 判无效、生产判有效。
        val javaTrimSaysInvalid = listOf(
            "\u2003" + "x".repeat(120) + "\u2003",
            "\u3000" + "x".repeat(120) + "\u3000",
            "\u00A0" + "x".repeat(120)
        )
        for (title in javaTrimSaysInvalid) {
            assertFalse(javaTrimValid(title), "java-trim 模型应判无效：首码点=${title.first().code}")
            assertTrue(
                GroupPkValidationPolicy.isValidTitle(title),
                "生产侧应判有效：首码点=${title.first().code}"
            )
        }
        // NUL 不是 Kotlin 空白，不会被 trim；java-trim 会剥掉，于是两者相反。
        val nulPrefixed = listOf(
            "\u0000" + "x".repeat(120),
            "\u0001" + "x".repeat(120)
        )
        for (title in nulPrefixed) {
            assertTrue(javaTrimValid(title), "java-trim 模型应判有效：首码点=${title.first().code}")
            assertFalse(
                GroupPkValidationPolicy.isValidTitle(title),
                "生产侧应判无效：首码点=${title.first().code}"
            )
        }
    }

    @Test
    fun `non-whitespace controls neither trim nor blank`() {
        val validSingles = listOf("\u0000", "\u0001", "\u0007")
        for (title in validSingles) {
            assertTrue(
                GroupPkValidationPolicy.isValidTitle(title),
                "单个非空白控制字符不是空白、长度 1，应有效：码点=${title.map { it.code }}"
            )
        }
        assertTrue(GroupPkValidationPolicy.isValidTitle("\u0001" + "x".repeat(119)), "控制字符 + 119 字恰 120 字，有效")
        assertFalse(GroupPkValidationPolicy.isValidTitle("\u0001" + "x".repeat(120)), "控制字符不 trim，+ 120 字共 121 字，无效")
    }

    @Test
    fun `both-sides pairs with quirk chars`() {
        assertTrue(GroupPkValidationPolicy.isValidTitles("a", "b"), "两侧普通标题有效")
        assertFalse(GroupPkValidationPolicy.isValidTitles("\u2003", "b"), "左侧纯 EM SPACE 无效")
        assertFalse(GroupPkValidationPolicy.isValidTitles("a", "\u00A0"), "右侧单个 NBSP 是空白，无效")
        assertTrue(GroupPkValidationPolicy.isValidTitles("\u200B", "\uFEFF"), "两侧零宽字符均有效")
        assertTrue(
            GroupPkValidationPolicy.isValidTitles("x".repeat(120), "\u2003" + "x".repeat(120)),
            "右侧 EM SPACE + 120 字 trim 后 120 字，有效"
        )
        assertTrue(
            GroupPkValidationPolicy.isValidTitles("\u2003" + "x".repeat(118) + "\u2003", "x"),
            "左侧 EM SPACE + 118 字 trim 后 118 字，有效"
        )
        assertFalse(GroupPkValidationPolicy.isValidTitles("", "a"), "左侧空串无效")
        assertFalse(GroupPkValidationPolicy.isValidTitles("x".repeat(121), "a"), "左侧 121 字无效")
    }
}
