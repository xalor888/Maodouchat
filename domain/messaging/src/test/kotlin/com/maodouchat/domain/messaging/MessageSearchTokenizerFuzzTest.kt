package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MessageSearchTokenizer 分词 fuzz（清单 931 行第十五块，簇 1）。
 *
 * 源码实测的不变式（只用安全字母表：ASCII 字母/数字、空白与分隔符、CJK 常用字——
 * 不含组合记号/全角字符，避免 NFKC 在词中途重组破坏单调性）：
 * - 词中切分会丢 token（如 "9b" 切在 "9|b" 处，"9" 在全串里不再出现），故单调性
 *   只在词边界（空格分隔的完整词）处断言，Python 迷你模型 20000 次零失配；
 * - 单个拉丁词与短汉字串有精确 oracle（截断 40 + 前缀 3..8 / 单字+二元+三元）；
 * - token 数恒 ≤ 200、恒无空串、恒无大写字母、恒 ≤ 40 字符。
 */
class MessageSearchTokenizerFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
        private val textFragments = listOf(
            "hello", "HELLO", "World", "a", "ab", "abc", "ok", "go", "deploy", "release",
            "中", "文", "今天", "天气", "下周", "项目", "会议", " ", "  ", "\t", "\n",
            ",", ".", ";", "!", "?", ":", "(", ")", "1", "42", "x", "Z", "a1", "9b"
        )
        private val hanChars = listOf('中', '文', '天', '气', '下', '周', '会', '议')
        private val latinLetters = ('a'..'z').toList()
    }

    private fun randomText(random: Random): String {
        val count = random.nextInt(0, 24)
        return buildString {
            repeat(count) { append(textFragments[random.nextInt(textFragments.size)]) }
        }
    }

    @Test
    fun `randomTextNeverThrowsAndStaysWithinCaps`() {
        val random = Random(0x15_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val text = randomText(random)
            val first = MessageSearchTokenizer.tokens(text)
            val second = MessageSearchTokenizer.tokens(text)
            assertEquals(first, second, "fuzz 迭代 #$i：同一文本两次分词必须相同")
            assertTrue(first.size <= 200, "fuzz 迭代 #$i：token 数不得超 200，实际 ${first.size}")
            assertTrue(first.none { it.isEmpty() }, "fuzz 迭代 #$i：不得产空串 token")
            assertTrue(
                first.none { token -> token.any { it.isUpperCase() } },
                "fuzz 迭代 #$i：分词后不得残留大写字母，实际 $first"
            )
            assertTrue(first.all { it.length <= 40 }, "fuzz 迭代 #$i：token 不得超 40 字符")
            assertEquals(first.size, first.toSet().size, "fuzz 迭代 #$i：token 不得重复")
        }
    }

    @Test
    fun `singleLatinWordMatchesExactOracle`() {
        val random = Random(0x15_2026)
        repeat(150) { i ->
            val length = random.nextInt(1, 61)
            val word = buildString {
                repeat(length) { append(latinLetters[random.nextInt(latinLetters.size)]) }
            }
            val truncated = word.take(40)
            val expected = mutableSetOf(truncated)
            if (truncated.length > 3) {
                for (prefixLength in 3..minOf(8, truncated.length - 1)) {
                    expected += truncated.take(prefixLength)
                }
            }
            assertEquals(
                expected, MessageSearchTokenizer.tokens(word).toSet(),
                "fuzz 迭代 #$i：单拉丁词 $word 的 token 集必须与 oracle 全等"
            )
        }
    }

    @Test
    fun `shortHanRunMatchesExactOracle`() {
        val random = Random(0x15_2026)
        repeat(150) { i ->
            val length = random.nextInt(1, 9)
            val run = buildString {
                repeat(length) { append(hanChars[random.nextInt(hanChars.size)]) }
            }
            val expected = mutableSetOf<String>()
            run.forEach { expected += it.toString() }
            for (size in 2..3) {
                if (run.length >= size) {
                    for (index in 0..run.length - size) expected += run.substring(index, index + size)
                }
            }
            assertEquals(
                expected, MessageSearchTokenizer.tokens(run).toSet(),
                "fuzz 迭代 #$i：短汉字串 $run 的 token 集必须与 oracle 全等"
            )
        }
    }

    @Test
    fun `wordBoundaryPrefixTokensAreSubsetOfFullText`() {
        val random = Random(0x15_2026)
        repeat(150) { i ->
            val wordCount = random.nextInt(2, 8)
            val words = List(wordCount) {
                if (random.nextBoolean()) {
                    buildString {
                        repeat(random.nextInt(1, 11)) {
                            append(latinLetters[random.nextInt(latinLetters.size)])
                        }
                    }
                } else {
                    buildString {
                        repeat(random.nextInt(1, 6)) {
                            append(hanChars[random.nextInt(hanChars.size)])
                        }
                    }
                }
            }
            val full = words.joinToString(" ")
            val fullTokens = MessageSearchTokenizer.tokens(full).toSet()
            assertTrue(fullTokens.size < 200, "fuzz 迭代 #$i：构造输入不得触顶，无法断言单调性")
            val split = random.nextInt(1, words.size)
            val prefixTokens = MessageSearchTokenizer.tokens(words.take(split).joinToString(" ")).toSet()
            assertTrue(
                prefixTokens.all { it in fullTokens },
                "fuzz 迭代 #$i：词边界前缀的 token 必须是全串 token 的子集，前缀 $prefixTokens，全串 $fullTokens"
            )
        }
    }

    @Test
    fun `separatorOnlyInputYieldsEmpty`() {
        val random = Random(0x15_2026)
        val separators = listOf(" ", "   ", "\t\n", "!!!???", ",.;:", " \t , ")
        separators.forEach { assertTrue(MessageSearchTokenizer.tokens(it).isEmpty(), "分隔符-only 输入 $it 必须为空") }
        repeat(50) { i ->
            val text = buildString {
                repeat(random.nextInt(1, 20)) {
                    append(listOf(" ", ",", ".", "!", "?", ";", ":", "\t")[random.nextInt(8)])
                }
            }
            assertTrue(
                MessageSearchTokenizer.tokens(text).isEmpty(),
                "fuzz 迭代 #$i：随机分隔符串必须为空，实际 ${MessageSearchTokenizer.tokens(text)}"
            )
        }
    }

    @Test
    fun `tokenCapIs200AndLongInputTerminates`() {
        val shuffled = ('一'..'龥').take(300).shuffled(Random(0x15_2026)).joinToString("")
        assertEquals(200, MessageSearchTokenizer.tokens(shuffled).size, "300 个不同汉字必须恰好截到 200 token")
        val longLatin = "a".repeat(5000)
        assertEquals(
            setOf("a".repeat(40), "aaa", "aaaa", "aaaaa", "aaaaaa", "aaaaaaa", "aaaaaaaa"),
            MessageSearchTokenizer.tokens(longLatin).toSet(),
            "5000 字符单拉丁词只取前 4000 再截 40，且必须终止"
        )
    }
}
