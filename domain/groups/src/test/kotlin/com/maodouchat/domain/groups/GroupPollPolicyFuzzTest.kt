package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupPollPolicyFuzzTest {

    private companion object {
        private const val ITERATIONS = 300
        private val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_:.".toList()
        private val CHOICES = listOf(
            "left", "right", "LEFT", "Right", "middle", "", " left ", "RIGHT ", "lEfT", "LEFT ", "right ",
        )
        private val CHECKIN_IDS = listOf("", " ", "c1", " c1 ", "a:b", "x y", "g-1_2")
        private val CHAIN_CONTENT_CHARS = (
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 |^ \t" +
                "，。！？、；：…—·" + "😀🎉"
            ).toList() + listOf('\u0001', '\u0002')
    }

    private fun randomId(random: Random, minLen: Int = 1, maxLen: Int = 12): String =
        buildString {
            repeat(random.nextInt(minLen, maxLen + 1)) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.size)]) }
        }

    private fun randomChainContent(random: Random): String =
        buildString {
            repeat(random.nextInt(0, 13)) { append(CHAIN_CONTENT_CHARS[random.nextInt(CHAIN_CONTENT_CHARS.size)]) }
        }

    private fun isKotlinWhitespace(c: Char): Boolean =
        c in '\u0009'..'\u000D' || c == ' ' || c in '\u001C'..'\u001F' ||
            c == '\u1680' || c in '\u2000'..'\u200A' ||
            c == '\u2028' || c == '\u2029' || c == '\u205F' || c == '\u3000' ||
            c == '\u00A0' || c == '\u2007' || c == '\u202F'

    // parse 前会对整串 trim，只会剥掉尾部真正的 Kotlin 空白。esc 后的 \u0001/\u0002
    // 不是空白，故原文尾部的 |/^ 会保留；先按 Kotlin 空白裁尾，再做还原。
    private fun expectedChainContent(content: String): String =
        content.dropLastWhile { isKotlinWhitespace(it) }
            .replace("\u0001", "|").replace("\u0002", "^")

    @Test
    fun `vote shortcuts round-trip with normalization under random indexes`() {
        val random = Random(93103)
        repeat(ITERATIONS) {
            val pollId = randomId(random)
            val indexes = List(random.nextInt(0, 7)) { random.nextInt(-5, 16) }
            val text = GroupPollPolicy.buildVoteShortcut(pollId, indexes)
            val expected = indexes.filter { it >= 0 }.distinct()
            if (expected.isEmpty()) {
                assertNull(GroupPollPolicy.parseVoteShortcut(text))
                assertFalse(GroupPollPolicy.isVoteShortcut(text))
            } else {
                assertEquals(pollId to expected, GroupPollPolicy.parseVoteShortcut(text))
                assertTrue(GroupPollPolicy.isVoteShortcut(text))
            }
        }
    }

    @Test
    fun `vote parsing drops garbage tokens and rejects blank ids`() {
        assertEquals("id" to listOf(2, 3), GroupPollPolicy.parseVoteShortcut("~vote:id|2, x, +3, 99999999999999999999, , -1"))
        assertEquals("id" to listOf(0, 1), GroupPollPolicy.parseVoteShortcut("~vote:id|0,0,1"))
        assertEquals("id" to listOf(0), GroupPollPolicy.parseVoteShortcut("  ~vote:id|0  "))
        assertEquals("a,b" to listOf(0), GroupPollPolicy.parseVoteShortcut("~vote:a,b|0"))
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:|0"))
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:id|"))
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:id|,,"))
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:id|-2,-3"))
        assertNull(GroupPollPolicy.parseVoteShortcut("hello"))
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:id"))
        assertFalse(GroupPollPolicy.isVoteShortcut("~vote:|0"))
        assertTrue(GroupPollPolicy.isVoteShortcut("~vote:id|0"))
    }

    @Test
    fun `chain shortcuts round-trip with escaping under random content`() {
        val random = Random(93113)
        repeat(ITERATIONS) {
            val chainId = if (random.nextInt(10) == 0) "" else randomId(random)
            val content = randomChainContent(random)
            val text = GroupPollPolicy.buildChainShortcut(chainId, content)
            val expectedContent = expectedChainContent(content)
            if (chainId.isBlank() || expectedContent.isBlank()) {
                assertNull(GroupPollPolicy.parseChainShortcut(text))
                assertFalse(GroupPollPolicy.isChainShortcut(text))
            } else {
                assertEquals(chainId to expectedContent, GroupPollPolicy.parseChainShortcut(text))
                assertTrue(GroupPollPolicy.isChainShortcut(text))
            }
        }
    }

    @Test
    fun `pk and checkin shortcuts round-trip under random choices`() {
        val random = Random(93123)
        repeat(ITERATIONS) {
            val pkId = randomId(random)
            val choice = CHOICES[random.nextInt(CHOICES.size)]
            val expectedChoice = choice.lowercase().takeIf { it == "left" || it == "right" } ?: "left"
            val pkText = GroupPollPolicy.buildPkShortcut(pkId, choice)
            assertEquals(pkId to expectedChoice, GroupPollPolicy.parsePkShortcut(pkText))
            assertTrue(GroupPollPolicy.isPkShortcut(pkText))

            val chatId = CHECKIN_IDS[random.nextInt(CHECKIN_IDS.size)]
            val checkinText = GroupPollPolicy.buildCheckinShortcut(chatId)
            assertEquals(chatId.trim().takeIf { it.isNotBlank() }, GroupPollPolicy.parseCheckinShortcut(checkinText))
            assertTrue(GroupPollPolicy.isCheckinShortcut(checkinText))
        }
    }

    @Test
    fun `shortcut predicates agree with parsers under random text`() {
        val random = Random(93133)
        val fragments = listOf("~vote:", "~checkin", "~chain:", "~pk:", "~", ":", "|", "^", " ", "abc", "0", ",", "left", "\u0001")
        repeat(ITERATIONS) {
            val text = buildString {
                repeat(random.nextInt(0, 5)) { append(fragments[random.nextInt(fragments.size)]) }
            }
            assertEquals(GroupPollPolicy.parseVoteShortcut(text) != null, GroupPollPolicy.isVoteShortcut(text))
            assertEquals(GroupPollPolicy.parseChainShortcut(text) != null, GroupPollPolicy.isChainShortcut(text))
            assertEquals(GroupPollPolicy.parsePkShortcut(text) != null, GroupPollPolicy.isPkShortcut(text))
            assertEquals(
                GroupPollPolicy.parseCheckinShortcut(text) != null || text.trim() == GroupPollPolicy.CHECKIN_PREFIX,
                GroupPollPolicy.isCheckinShortcut(text),
            )
        }
        // 显式钉住 checkin 的特殊分支：裸 ~checkin 解析为 null，但谓词为真。
        assertNull(GroupPollPolicy.parseCheckinShortcut("~checkin"))
        assertTrue(GroupPollPolicy.isCheckinShortcut("~checkin"))
        assertNull(GroupPollPolicy.parseCheckinShortcut("~checkin:"))
        assertFalse(GroupPollPolicy.isCheckinShortcut("~checkin:"))
        assertEquals("x:y", GroupPollPolicy.parseCheckinShortcut("~checkin:x:y"))
        assertEquals("chat1", GroupPollPolicy.parseCheckinShortcut("  ~checkin: chat1  "))
    }

    @Test
    fun `poll question and option validation matches independent rules under random input`() {
        val random = Random(93143)
        val optionPool = listOf(
            "A", "a", "B", " b ", "", "   ", "x".repeat(80), "y".repeat(81),
            "选项", "选项", "C", "c", "hello", "Hello",
        )
        repeat(ITERATIONS) {
            val question = buildString {
                val pieces = listOf("a", "b", " ", "\t", "中", "😀")
                repeat(random.nextInt(0, 212)) {
                    append(pieces[random.nextInt(pieces.size)])
                }
            }.let { if (random.nextInt(4) == 0) "  $it  " else it }
            val expectedQuestion = question.isNotBlank() && question.trim().length <= 200
            assertEquals(expectedQuestion, GroupPollPolicy.isValidPollQuestion(question))

            val options = List(random.nextInt(0, 15)) { optionPool[random.nextInt(optionPool.size)] }
            val cleaned = options.map { it.trim() }.filter { it.isNotBlank() }
            val expectedOptions = cleaned.size in 2..12 &&
                cleaned.all { it.length <= 80 } &&
                cleaned.map { it.lowercase() }.distinct().size == cleaned.size
            assertEquals(expectedOptions, GroupPollPolicy.isValidPollOptions(options))

            val sanitized = GroupPollPolicy.sanitizePollOptions(options)
            assertEquals(cleaned.map { it.take(80) }, sanitized)
            assertTrue(sanitized.all { it.isNotBlank() && it == it.trim() && it.length <= 80 })
            assertEquals(
                sanitized.size in 2..12 && sanitized.map { it.lowercase() }.distinct().size == sanitized.size,
                GroupPollPolicy.isValidPollOptions(sanitized),
            )
        }
    }

    @Test
    fun `pinned separators blanks and share text formats`() {
        // chainId 里的 | 不转义：解析时首个 | 后的全部内容都归入 content。
        assertEquals("a" to "b|c", GroupPollPolicy.parseChainShortcut(GroupPollPolicy.buildChainShortcut("a|b", "c")))
        assertEquals("~pk:id|left", GroupPollPolicy.buildPkShortcut("id", " left "))
        assertEquals("id" to "left", GroupPollPolicy.parsePkShortcut("~pk:id|LEFT"))
        assertNull(GroupPollPolicy.parsePkShortcut("~pk:id|middle"))
        assertNull(GroupPollPolicy.parsePkShortcut("~pk:|left"))
        assertEquals(
            "📊 群投票 · ${"q".repeat(200)}\n1. A\n2. B\n（可多选）\n~vote:p1|0",
            GroupPollPolicy.formatPollShare("p1", "q".repeat(250), listOf("A", "B"), true),
        )
        assertEquals(
            "📊 群投票 · 投票\n1. A\n~vote:p1|0",
            GroupPollPolicy.formatPollShare("p1", "投票", listOf("A"), false),
        )
        assertEquals("~checkin 阿明 已签到 · 连续 3 天", GroupPollPolicy.formatCheckinShare("阿明", 3))
        assertEquals(
            "🔗 群接龙 · 标题\n话|题\n第 2 条：~chain:c1|话\u0001题",
            GroupPollPolicy.formatChainShare("c1", "标题", "话|题", 2),
        )
        assertEquals(
            "⚔️ 群 PK\n~pk:k1|left · ~pk:k1|right\n左 vs 右",
            GroupPollPolicy.formatPkShare("k1", "左", "右"),
        )
    }
}
